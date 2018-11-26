package org.tron.core.net.peer;

import static org.tron.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;
import static org.tron.core.config.Parameter.NetConstants.MAX_TRX_FETCH_PER_PEER;
import static org.tron.core.config.Parameter.NetConstants.MSG_CACHE_DURATION_IN_BLOCKS;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.overlay.discover.node.statistics.MessageCount;
import org.tron.common.overlay.message.Message;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.Time;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.net.TronProxy;
import org.tron.core.net.message.BlockMessage;
import org.tron.core.net.message.FetchInvDataMessage;
import org.tron.core.net.message.InventoryMessage;
import org.tron.core.net.message.TransactionMessage;
import org.tron.protos.Protocol.Inventory.InventoryType;

@Slf4j
@Component
public class PeerAdv {

  @Autowired
  private TronProxy tronProxy;

  private ConcurrentHashMap<Item, Long> invToFetch = new ConcurrentHashMap<>();

  private ConcurrentHashMap<Item, Long> invToSpread = new ConcurrentHashMap<>();
  
  private Cache<Item, Long> invToFetchCache = CacheBuilder.newBuilder()
      .maximumSize(500_000).expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();

  private Cache<Item, Message> messageCache = CacheBuilder.newBuilder()
      .maximumSize(100_000).expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();

//  private Cache<Sha256Hash, TransactionMessage> trxCache = CacheBuilder.newBuilder()
//      .maximumSize(50_000).expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(50_000)
//      .recordStats().build();
//
//  private Cache<Sha256Hash, BlockMessage> blockCache = CacheBuilder.newBuilder()
//      .maximumSize(10).expireAfterWrite(60, TimeUnit.SECONDS)
//      .recordStats().build();

  private ScheduledExecutorService spreadExecutor = Executors.newSingleThreadScheduledExecutor();

  private ScheduledExecutorService fetchExecutor = Executors.newSingleThreadScheduledExecutor();

  @Getter
  private MessageCount trxCount = new MessageCount();

  public void init () {
    spreadExecutor.scheduleWithFixedDelay(() -> {
      try {
        consumerInvToSpread();
      } catch (Throwable t) {
        logger.error("Spread thread error.", t);
      }
    }, 100, 30, TimeUnit.MILLISECONDS);

    fetchExecutor.scheduleWithFixedDelay(() -> {
      try {
        consumerInvToFetch();
      } catch (Throwable t) {
        logger.error("Fetch thread error.", t);
      }
    }, 100, 30, TimeUnit.MILLISECONDS);
  }

  public void close () {
    spreadExecutor.shutdown();
    fetchExecutor.shutdown();
    logger.info("PeerAdv closed.");
  }

  synchronized public boolean addInv (Item item) {
    if (invToFetchCache.getIfPresent(item) != null) {
      return false;
    }
    if (messageCache.getIfPresent(item) != null) {
      return false;
    }
    invToFetchCache.put(item, System.currentTimeMillis());
    invToFetch.put(item, System.currentTimeMillis());
    return true;
  }

  public Message getMessage (Item item) {
    return messageCache.getIfPresent(item);
  }

  public void broadcast(Message msg) {
    Item item;
    if (msg instanceof BlockMessage) {
      BlockMessage blockMsg = (BlockMessage) msg;
      item = new Item(blockMsg.getMessageId(), InventoryType.BLOCK);
      logger.info("Ready to broadcast block {}", blockMsg.getBlockId().getString());
      blockMsg.getBlockCapsule().getTransactions().forEach(transactionCapsule ->
        invToSpread.remove(transactionCapsule.getTransactionId())
      );
    } else if (msg instanceof TransactionMessage) {
      TransactionMessage trxMsg = (TransactionMessage) msg;
      item = new Item(trxMsg.getMessageId(), InventoryType.TRX);
      trxCount.add();
    } else {
      logger.error("Adv item is neither block nor trx, type: {}", msg.getType());
      return;
    }
    messageCache.put(item, msg);
    synchronized (invToSpread) {
      invToSpread.put(item, System.currentTimeMillis());
    }
  }

  public void onDisconnect (PeerConnection peer) {
    if (!peer.getAdvInvRequest().isEmpty()) {
      peer.getAdvInvRequest().keySet().forEach(item -> {
        if (tronProxy.getActivePeer().stream()
            .filter(peerConnection -> !peerConnection.equals(peer))
            .filter(peerConnection -> peerConnection.getAdvInvReceive().containsKey(item))
            .findFirst()
            .isPresent()) {
          invToFetch.put(item, System.currentTimeMillis());
        }
      });
    }
  }

  private void consumerInvToFetch() {
    Collection<PeerConnection> peers = tronProxy.getActivePeer().stream()
        .filter(peer -> peer.isIdle() && !peer.isNeedSyncFromPeer() && !peer.isNeedSyncFromUs())
        .collect(Collectors.toList());

    if (invToFetch.isEmpty() || peers.isEmpty()) {
      return;
    }

    InvSender invSender = new InvSender();
    long now = Time.getCurrentMillis();
    invToFetch.forEach((item, time) -> {
      Sha256Hash hash = item.getHash();
      if (time < now - MSG_CACHE_DURATION_IN_BLOCKS * BLOCK_PRODUCED_INTERVAL) {
        logger.info("This obj is too late to fetch, type: {} hash: {}.", item.getType(), item.getHash());
        invToFetch.remove(item);
        return;
      }
      peers.stream()
          .filter(peer -> peer.getAdvInvReceive().containsKey(hash) && invSender.getSize(peer) < MAX_TRX_FETCH_PER_PEER)
          .sorted(Comparator.comparingInt(peer -> invSender.getSize(peer)))
          .findFirst().ifPresent(peer -> {
        invSender.add(item, peer);
        peer.getAdvInvRequest().put(item, now);
        invToFetch.remove(item);
      });
    });

    invSender.sendFetch();
  }

  private void consumerInvToSpread() {
    if (invToSpread.isEmpty()) {
      return;
    }

    InvSender invSender = new InvSender();
    HashMap<Item, Long> spread = new HashMap<>();
    synchronized (invToSpread) {
      spread.putAll(invToSpread);
      invToSpread.clear();
    }

    tronProxy.getActivePeer().stream()
        .filter(peer -> !peer.isNeedSyncFromPeer() && !peer.isNeedSyncFromUs())
        .forEach(peer -> spread.entrySet().stream()
            .filter(entry -> !peer.getAdvInvReceive().containsKey(entry.getKey()) && !peer.getAdvInvSpread().containsKey(entry.getKey()))
            .forEach(entry -> {
              peer.getAdvInvSpread().put(entry.getKey(), Time.getCurrentMillis());
              invSender.add(entry.getKey(), peer);
            }));

    invSender.sendInv();
  }

  class InvSender {

    private HashMap<PeerConnection, HashMap<InventoryType, LinkedList<Sha256Hash>>> send = new HashMap<>();

    public void clear() {
      this.send.clear();
    }

    public void add(Entry<Sha256Hash, InventoryType> id, PeerConnection peer) {
      if (send.containsKey(peer) && !send.get(peer).containsKey(id.getValue())) {
        send.get(peer).put(id.getValue(), new LinkedList<>());
      } else if (!send.containsKey(peer)) {
        send.put(peer, new HashMap<>());
        send.get(peer).put(id.getValue(), new LinkedList<>());
      }
      send.get(peer).get(id.getValue()).offer(id.getKey());
    }

    public void add(Item id, PeerConnection peer) {
      if (send.containsKey(peer) && !send.get(peer).containsKey(id.getType())) {
        send.get(peer).put(id.getType(), new LinkedList<>());
      } else if (!send.containsKey(peer)) {
        send.put(peer, new HashMap<>());
        send.get(peer).put(id.getType(), new LinkedList<>());
      }
      send.get(peer).get(id.getType()).offer(id.getHash());
    }

    public int getSize(PeerConnection peer) {
      if (send.containsKey(peer)) {
        return send.get(peer).values().stream().mapToInt(LinkedList::size).sum();
      }
      return 0;
    }

    public void sendInv() {
      send.forEach((peer, ids) -> ids.forEach((key, value) -> {
        if (key.equals(InventoryType.BLOCK)) {
          value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum()));
        }
        peer.sendMessage(new InventoryMessage(value, key));
      }));
    }

    void sendFetch() {
      send.forEach((peer, ids) -> ids.forEach((key, value) -> {
        if (key.equals(InventoryType.BLOCK)) {
          value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum()));
        }
        peer.sendMessage(new FetchInvDataMessage(value, key));
      }));
    }
  }

}
