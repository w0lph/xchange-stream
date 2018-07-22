package info.bitrich.xchangestream.binance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.bitrich.xchangestream.binance.dto.*;
import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import info.bitrich.xchangestream.service.netty.StreamingObjectMapperHelper;
import io.reactivex.Observable;
import io.reactivex.functions.Consumer;
import org.knowm.xchange.binance.BinanceAdapters;
import org.knowm.xchange.binance.dto.marketdata.BinanceOrderbook;
import org.knowm.xchange.binance.dto.marketdata.BinanceTicker24h;
import org.knowm.xchange.binance.service.BinanceMarketDataService;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.OrderBookUpdate;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.marketdata.Trade;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static info.bitrich.xchangestream.binance.dto.BaseBinanceWebSocketTransaction.BinanceWebSocketTypes.*;

public class BinanceStreamingMarketDataService implements StreamingMarketDataService {
    private static final Logger LOG = LoggerFactory.getLogger(BinanceStreamingMarketDataService.class);
    private static final OrderBook EMPTY_ORDER_BOOK = new OrderBook(null, Collections.EMPTY_LIST, Collections.EMPTY_LIST);
    private final BinanceStreamingService service;
    private final BinanceMarketDataService marketDataService;
    private final Map<CurrencyPair, OrderBook> orderbooks = new HashMap<>();

    private final Map<CurrencyPair, Observable<BinanceTicker24h>> tickerSubscriptions = new HashMap<>();
    private final Map<CurrencyPair, Observable<OrderBook>> orderbookSubscriptions = new HashMap<>();
    private final Map<CurrencyPair, Observable<BinanceRawTrade>> tradeSubscriptions = new HashMap<>();
    private final ObjectMapper mapper = StreamingObjectMapperHelper.getObjectMapper();


    public BinanceStreamingMarketDataService(BinanceStreamingService service, BinanceMarketDataService marketDataService) {
        this.service = service;
        this.marketDataService = marketDataService;
    }

    @Override
    public Observable<OrderBook> getOrderBook(CurrencyPair currencyPair, Object... args) {
        if (!service.getProductSubscription().getOrderBook().contains(currencyPair)) {
            throw new UnsupportedOperationException("Binance exchange only supports up front subscriptions - subscribe at connect time");
        }
        return orderbookSubscriptions.get(currencyPair);
    }

    public Observable<BinanceTicker24h> getRawTicker(CurrencyPair currencyPair, Object... args) {
        if (!service.getProductSubscription().getTicker().contains(currencyPair)) {
            throw new UnsupportedOperationException("Binance exchange only supports up front subscriptions - subscribe at connect time");
        }
        return tickerSubscriptions.get(currencyPair);
    }

    public Observable<BinanceRawTrade> getRawTrades(CurrencyPair currencyPair, Object... args) {
        if (!service.getProductSubscription().getTrades().contains(currencyPair)) {
            throw new UnsupportedOperationException("Binance exchange only supports up front subscriptions - subscribe at connect time");
        }
        return tradeSubscriptions.get(currencyPair);
    }

    @Override
    public Observable<Ticker> getTicker(CurrencyPair currencyPair, Object... args) {
        return getRawTicker(currencyPair)
                .map(BinanceTicker24h::toTicker);
    }

    @Override
    public Observable<Trade> getTrades(CurrencyPair currencyPair, Object... args) {
        return getRawTrades(currencyPair)
                .map(rawTrade -> new Trade(
                        BinanceAdapters.convertType(rawTrade.isBuyerMarketMaker()),
                        rawTrade.getQuantity(),
                        currencyPair,
                        rawTrade.getPrice(),
                        new Date(rawTrade.getTimestamp()),
                        String.valueOf(rawTrade.getTradeId())
                ));
    }

    private static String channelFromCurrency(CurrencyPair currencyPair, String subscriptionType) {
        String currency = String.join("", currencyPair.toString().split("/")).toLowerCase();
        return currency + "@" + subscriptionType;
    }

    /**
     * Registers subsriptions with the streaming service for the given products.
     *
     * As we receive messages as soon as the connection is open, we need to register subscribers to handle these before the
     * first messages arrive.
     */
    public void openSubscriptions(ProductSubscription productSubscription) {
        productSubscription.getTicker()
                .forEach(currencyPair ->
                        tickerSubscriptions.put(currencyPair, triggerObservableBody(rawTickerStream(currencyPair).share())));
        productSubscription.getOrderBook()
                .forEach(currencyPair ->
                        orderbookSubscriptions.put(currencyPair, triggerObservableBody(orderBookStream(currencyPair).share())));
        productSubscription.getTrades()
                .forEach(currencyPair ->
                        tradeSubscriptions.put(currencyPair, triggerObservableBody(rawTradeStream(currencyPair).share())));
    }

    private Observable<BinanceTicker24h> rawTickerStream(CurrencyPair currencyPair) {
        return service.subscribeChannel(channelFromCurrency(currencyPair, "ticker"))
                .map((JsonNode s) -> tickerTransaction(s.toString()))
                .filter(transaction ->
                        transaction.getData().getCurrencyPair().equals(currencyPair) &&
                            transaction.getData().getEventType() == TICKER_24_HR)
                .map(transaction -> transaction.getData().getTicker());
    }

    private long lastUpdateId;
    private BinanceOrderbook initialSnapshot;
    private TreeSet<DepthBinanceWebSocketTransaction> cachedTransactionData = new TreeSet<>(new Comparator<DepthBinanceWebSocketTransaction>() {
        @Override
        public int compare(DepthBinanceWebSocketTransaction o1, DepthBinanceWebSocketTransaction o2) {
            return Long.compare(o1.getFirstUpdateId(), o2.getFirstUpdateId());
        }
    });

    private Observable<OrderBook> orderBookStream(CurrencyPair currencyPair) {
        return service.subscribeChannel(channelFromCurrency(currencyPair, "depth"))
                .map((JsonNode s) -> depthTransaction(s.toString()))
                .filter(transaction ->
                        transaction.getData().getCurrencyPair().equals(currencyPair) &&
                                transaction.getData().getEventType() == DEPTH_UPDATE)
                .map(transaction -> {
                    DepthBinanceWebSocketTransaction depth = transaction.getData();
                    if (lastUpdateId > 0 && depth.getFirstUpdateId() != lastUpdateId + 1) {
                        LOG.error("Binance GAP detected {} {}. Recovering is not implemented", lastUpdateId, depth.getFirstUpdateId());
                    }
                    lastUpdateId = depth.getOrderBook().lastUpdateId;
                    OrderBook currentOrderBook = orderbooks.get(currencyPair);
                    if (currentOrderBook == null) {
                        cachedTransactionData.add(depth);

                        if (initialSnapshot == null) {
                            initialSnapshot = marketDataService.getBinanceOrderbook(currencyPair, 100);
                        }
                        long lastUpdateId = initialSnapshot.lastUpdateId;
                        if (lastUpdateId + 1 < cachedTransactionData.first().getFirstUpdateId()) {
                            initialSnapshot = null;
                            return EMPTY_ORDER_BOOK;
                        }
                        for (DepthBinanceWebSocketTransaction cachedTransactionData : cachedTransactionData) {
                            if (cachedTransactionData.getFirstUpdateId() <= lastUpdateId + 1 && lastUpdateId + 1 <=cachedTransactionData.getOrderBook().lastUpdateId) {
                                SortedSet<DepthBinanceWebSocketTransaction> depthBinanceWebSocketTransactions = this.cachedTransactionData.tailSet(cachedTransactionData);
                                currentOrderBook = convertBook(currencyPair, initialSnapshot);
                                for (DepthBinanceWebSocketTransaction depthBinanceWebSocketTransaction : depthBinanceWebSocketTransactions) {
                                    applyBookUpdate(currencyPair, depthBinanceWebSocketTransaction, currentOrderBook);
                                }
                                initialSnapshot = null;
                                this.cachedTransactionData.clear();
                                orderbooks.put(currencyPair, currentOrderBook);
                                return currentOrderBook;
                            }
                        }
                        return EMPTY_ORDER_BOOK;
                    }

                    applyBookUpdate(currencyPair, depth, currentOrderBook);
                    return currentOrderBook;
                });
    }

    /**
     * todo move this code to xchange project
     */
    private static OrderBook convertBook(CurrencyPair pair, BinanceOrderbook binanceOrderBook) {
        List<LimitOrder> bids =
                binanceOrderBook.bids
                        .entrySet()
                        .stream()
                        .map(e -> new LimitOrder(OrderType.BID, e.getValue(), pair, null, null, e.getKey()))
                        .collect(Collectors.toList());
        List<LimitOrder> asks =
                binanceOrderBook.asks
                        .entrySet()
                        .stream()
                        .map(e -> new LimitOrder(OrderType.ASK, e.getValue(), pair, null, null, e.getKey()))
                        .collect(Collectors.toList());
        return new OrderBook(null, asks, bids);
    }


    private void applyBookUpdate(CurrencyPair currencyPair, DepthBinanceWebSocketTransaction depth, OrderBook currentOrderBook) {
        BinanceOrderbook ob = depth.getOrderBook();
        ob.bids.forEach((key, value) -> currentOrderBook.update(new OrderBookUpdate(
                OrderType.BID, null, currencyPair, key, depth.getEventTime(), value)));
        ob.asks.forEach((key, value) -> currentOrderBook.update(new OrderBookUpdate(
                OrderType.ASK, null, currencyPair,
                key, depth.getEventTime(), value)));


    }

    private Observable<BinanceRawTrade> rawTradeStream(CurrencyPair currencyPair) {
        return service.subscribeChannel(channelFromCurrency(currencyPair, "trade"))
                .map((JsonNode s) -> tradeTransaction(s.toString()))
                .filter(transaction ->
                        transaction.getData().getCurrencyPair().equals(currencyPair) &&
                                transaction.getData().getEventType() == TRADE
                )
                .map(transaction -> transaction.getData().getRawTrade());
    }

    /** Force observable to execute its body, this way we get `BinanceStreamingService` to register the observables emitter
     * ready for our message arrivals. */
    private <T> Observable<T> triggerObservableBody(Observable<T> observable) {
        Consumer<T> NOOP = whatever -> {};
        observable.subscribe(NOOP);
        return observable;
    }

    private BinanceWebsocketTransaction<TickerBinanceWebsocketTransaction> tickerTransaction(String s) {
        try {
            return mapper.readValue(s, new TypeReference<BinanceWebsocketTransaction<TickerBinanceWebsocketTransaction>>() {});
        } catch (IOException e) {
            throw new ExchangeException("Unable to parse ticker transaction", e);
        }
    }

    private BinanceWebsocketTransaction<DepthBinanceWebSocketTransaction> depthTransaction(String s) {
        try {
            return mapper.readValue(s, new TypeReference<BinanceWebsocketTransaction<DepthBinanceWebSocketTransaction>>() {});
        } catch (IOException e) {
          throw new ExchangeException("Unable to parse order book transaction", e);
        }
    }

    private BinanceWebsocketTransaction<TradeBinanceWebsocketTransaction> tradeTransaction(String s) {
        try {
            return mapper.readValue(s, new TypeReference<BinanceWebsocketTransaction<TradeBinanceWebsocketTransaction>>() {});
        } catch (IOException e) {
            throw new ExchangeException("Unable to parse trade transaction", e);
        }
    }

}
