package io.horizontalsystems.xrateskit.providers.coingecko

import io.horizontalsystems.coinkit.models.CoinType
import io.horizontalsystems.xrateskit.coins.CoinInfoManager
import io.horizontalsystems.xrateskit.coins.ProviderCoinError
import io.horizontalsystems.xrateskit.coins.ProviderCoinsManager
import io.horizontalsystems.xrateskit.core.*
import io.horizontalsystems.xrateskit.entities.*
import io.horizontalsystems.xrateskit.providers.InfoProvider
import io.horizontalsystems.xrateskit.utils.RetrofitUtils
import io.reactivex.Single
import java.math.BigDecimal
import java.util.*
import java.util.logging.Logger
import kotlin.math.absoluteValue

class CoinGeckoProvider(
    private val factory: Factory,
    private val coinInfoManager: CoinInfoManager,
    private val providerCoinsManager: ProviderCoinsManager
) : ICoinMarketProvider, IChartInfoProvider, ILatestRateProvider, IHistoricalRateProvider {

    private val logger = Logger.getLogger("CoinGeckoProvider")

    override val provider: InfoProvider = InfoProvider.CoinGecko()
    private val MAX_ITEM_PER_PAGE = 250
    private val MINUTES_10_IN_SECONDS = 60 * 10
    private val HOURS_2_IN_SECONDS = 60 * 60 * 2
    private val exchangesOrdering by lazy {
        var i = 0

        hashMapOf(
            "binance" to ++i,
            "binance_us" to ++i,
            "binance_dex" to ++i,
            "binance_dex_mini" to ++i,
            "uniswap_v1" to ++i,
            "uniswap" to ++i,
            "gdax" to ++i, // Coinbase
            "sushiswap" to ++i,
            "huobi" to ++i,
            "huobi_thailand" to ++i,
            "huobi_id" to ++i,
            "huobi_korea" to ++i,
            "huobi_japan" to ++i,
            "ftx_spot" to ++i,
            "ftx_us" to ++i,
            "one_inch" to ++i,
            "one_inch_liquidity_protocol" to ++i,
            "one_inch_liquidity_protocol_bsc" to ++i,
        )
    }

    private val coinGeckoService: CoinGeckoService by lazy {
        RetrofitUtils.build(provider.baseUrl).create(CoinGeckoService::class.java)
    }


    init {
        initProvider()
    }

    override fun initProvider() {}
    override fun destroy() {}

    private fun getProviderCoinId(coinType: CoinType): String {
        providerCoinsManager.getProviderIds(listOf(coinType), this.provider).let {
            if(it.isNotEmpty()){
                it[0]?.let {
                    return it
                }
            }
        }

        logger.warning(" *** Error! Cannot get providerCoin for CoinType:${coinType.ID}")
        throw ProviderCoinError.NoMatchingExternalId()
    }

    private fun getCoinType(providerCoinId: String): CoinType? {
        return providerCoinsManager.getCoinTypes(providerCoinId.toLowerCase(Locale.ENGLISH), provider).firstOrNull()
    }

    override fun getTopCoinMarketsAsync(currencyCode: String, fetchDiffPeriod: TimePeriod, itemsCount: Int): Single<List<CoinMarket>> {
        try {
            var pageNumber = 1
            val singles = mutableListOf<Single<List<CoinMarket>>>()
            var requestItems = itemsCount

            do{
                singles.add(getCoinMarketsSingle(
                    currencyCode = currencyCode,
                    fetchDiffPeriod = fetchDiffPeriod,
                    itemsCount = if(requestItems > MAX_ITEM_PER_PAGE) MAX_ITEM_PER_PAGE else requestItems,
                    pageNumber = pageNumber)
                )

                requestItems -= MAX_ITEM_PER_PAGE
                pageNumber ++

            } while (requestItems > 0)


            return Single.zip(singles) { zippedObject ->
                zippedObject.toList().flatMap { it as List<CoinMarket>}
            }

        } catch (ex: Exception) {
            logger.warning(ex.localizedMessage)
        }

        return Single.just(emptyList())
    }

    private fun getCoinMarketsSingle( currencyCode: String, fetchDiffPeriod: TimePeriod, itemsCount: Int, pageNumber: Int): Single<List<CoinMarket>> {
        return getCoinMarkets(currencyCode,fetchDiffPeriod, itemsCount = itemsCount, pageNumber = pageNumber)
    }

    override fun getCoinMarketsAsync(coinTypes: List<CoinType>, currencyCode: String, fetchDiffPeriod: TimePeriod): Single<List<CoinMarket>> {
        val providerCoinIds = providerCoinsManager.getProviderIds(coinTypes, provider).filterNotNull()

        return when {
            providerCoinIds.isEmpty() -> Single.just(listOf())
            else -> getCoinMarkets(currencyCode,fetchDiffPeriod, coinIds = providerCoinIds)
        }
    }

    private fun getCoinMarkets(currencyCode: String, fetchDiffPeriod: TimePeriod, itemsCount: Int? = null, coinIds: List<String>? = null, pageNumber: Int = 1): Single<List<CoinMarket>> {
        val priceChangePercentage = listOf(fetchDiffPeriod)
            .filter { it != TimePeriod.ALL && it != TimePeriod.HOUR_24 }
            .map { it.title }
            .joinToString(",")

        return coinGeckoService.coinsMarkets(
            currencyCode,
            coinIds?.joinToString(","),
            "market_cap_desc",
            itemsCount,
            pageNumber,
            priceChangePercentage
        ).map {
            it.mapNotNull { convertCoinGeckoCoinMarket(it, currencyCode, fetchDiffPeriod) }
        }
    }

    private fun convertCoinGeckoCoinMarket(responseCoinMarket: CoinGeckoService.Response.CoinMarket, currencyCode: String, fetchDiffPeriod: TimePeriod): CoinMarket? {
        val type = getCoinType(responseCoinMarket.id) ?: return null

        val rateDiffPeriod = when (fetchDiffPeriod) {
            TimePeriod.HOUR_1 -> responseCoinMarket.price_change_percentage_1h_in_currency
            TimePeriod.HOUR_24 -> responseCoinMarket.price_change_percentage_24h
            TimePeriod.DAY_7 -> responseCoinMarket.price_change_percentage_7d_in_currency
            TimePeriod.DAY_14 -> responseCoinMarket.price_change_percentage_14d_in_currency
            TimePeriod.DAY_30 -> responseCoinMarket.price_change_percentage_30d_in_currency
            TimePeriod.DAY_200 -> responseCoinMarket.price_change_percentage_200d_in_currency
            TimePeriod.YEAR_1 -> responseCoinMarket.price_change_percentage_1y_in_currency
            else -> null
        } ?: BigDecimal.ZERO

        return factory.createCoinMarket(
            coinData = CoinData(type, responseCoinMarket.symbol.toUpperCase(Locale.ENGLISH), responseCoinMarket.name),
            currency = currencyCode,
            rate = responseCoinMarket.current_price,
            rateOpenDay = responseCoinMarket.price_change_24h,
            rateDiff = responseCoinMarket.price_change_percentage_24h ?: BigDecimal.ZERO,
            volume = responseCoinMarket.total_volume,
            supply = responseCoinMarket.circulating_supply,
            rateDiffPeriod = rateDiffPeriod,
            marketCap = responseCoinMarket.market_cap
        )
    }

    override fun getCoinMarketDetailsAsync(coinType: CoinType, currencyCode: String, rateDiffCoinCodes: List<String>, rateDiffPeriods: List<TimePeriod>): Single<CoinMarketDetails> {
        val providerCoinId = getProviderCoinId(coinType)
        return doCoinMarketDetailsRequest(providerCoinId, currencyCode, rateDiffCoinCodes, rateDiffPeriods)
            .map { coinMarketDetailsResponse ->
                val coinRating = coinInfoManager.getCoinRating(coinType)
                val categories = coinInfoManager.getCoinCategories(coinType)
                val funds = coinInfoManager.getCoinFundCategories(coinType)
                val links = coinInfoManager.getLinks(coinType, coinMarketDetailsResponse.coinInfo.links)

                CoinMarketDetails(
                    data = CoinData(
                        coinType,
                        coinMarketDetailsResponse.coinInfo.coinCode,
                        coinMarketDetailsResponse.coinInfo.title
                    ),
                    currencyCode = currencyCode,
                    rate = coinMarketDetailsResponse.coinGeckoMarkets.rate,
                    rateHigh24h = coinMarketDetailsResponse.coinGeckoMarkets.rateHigh24h,
                    rateLow24h = coinMarketDetailsResponse.coinGeckoMarkets.rateLow24h,
                    marketCap = coinMarketDetailsResponse.coinGeckoMarkets.marketCap,
                    marketCapDiff24h = coinMarketDetailsResponse.coinGeckoMarkets.marketCapDiff24h,
                    volume24h = coinMarketDetailsResponse.coinGeckoMarkets.volume24h,
                    circulatingSupply = coinMarketDetailsResponse.coinGeckoMarkets.circulatingSupply,
                    totalSupply = coinMarketDetailsResponse.coinGeckoMarkets.totalSupply,
                    meta = CoinMeta(
                        coinMarketDetailsResponse.coinInfo.description ?: "",
                        links,
                        coinRating,
                        categories,
                        funds,
                        coinMarketDetailsResponse.coinInfo.platforms
                    ),

                    rateDiffs = coinMarketDetailsResponse.rateDiffs,
                    tickers = coinMarketDetailsResponse.coinInfo.tickers
                        .filter { filterTicker(it) }
                        .sortedBy { exchangesOrdering[it.marketId] ?: Integer.MAX_VALUE }
                        .map {
                            MarketTicker(it.base, it.target, it.marketName, it.rate, it.volume)
                        }
                )
            }
    }

    private fun filterTicker(it: CoinGeckoTickersResponse) = when {
        it.rate.compareTo(BigDecimal.ZERO) == 0 -> false
        it.volume.compareTo(BigDecimal.ZERO) == 0 -> false
        isSmartContractAddress(it.base) -> false
        isSmartContractAddress(it.target) -> false
        else -> true
    }

    private fun isSmartContractAddress(v: String): Boolean {
        if (v.length != 42) return false

        return v.matches("^0[xX][A-z0-9]+$".toRegex())
    }

    private fun doCoinMarketDetailsRequest(coinId: String, currencyCode: String, rateDiffCoinCodes: List<String>, rateDiffPeriods: List<TimePeriod>): Single<CoinGeckoCoinMarketDetailsResponse> {
        return coinGeckoService.coin(coinId, "true", "false", "false")
            .map { coin ->

                val links = mutableMapOf<LinkType, String>()
                coin.links.apply {
                    homepage.firstOrNull()?.let {
                        links[LinkType.WEBSITE] = it
                    }

                    if (!twitter_screen_name.isNullOrBlank()) {
                        links[LinkType.TWITTER] = "https://twitter.com/${twitter_screen_name}"
                    }

                    if (!telegram_channel_identifier.isNullOrBlank()) {
                        links[LinkType.TELEGRAM] = "https://t.me/${telegram_channel_identifier}"
                    }

                    if (!subreddit_url.isNullOrBlank()) {
                        links[LinkType.REDDIT] = subreddit_url
                    }

                    repos_url["github"]?.firstOrNull()?.let {
                        links[LinkType.GITHUB] = it
                    }
                }

                val platforms = coin.platforms.mapNotNull {
                    if (it.value.isBlank()) return@mapNotNull null

                    val platformType = when (it.key.toLowerCase(Locale.ENGLISH)) {
                        "tron" -> CoinPlatformType.TRON
                        "ethereum" -> CoinPlatformType.ETHEREUM
                        "eos" -> CoinPlatformType.EOS
                        "binance-smart-chain" -> CoinPlatformType.BINANCE_SMART_CHAIN
                        "binancecoin" -> CoinPlatformType.BINANCE
                        else -> CoinPlatformType.OTHER
                    }

                    platformType to it.value
                }.toMap()

                val contractAddresses = platforms.map { it.value.toLowerCase(Locale.ENGLISH) }
                val tickers = coin.tickers.map {
                    val base = if (contractAddresses.contains(it.base.toLowerCase(Locale.ENGLISH))) {
                        coin.symbol
                    } else {
                        it.base
                    }

                    val target = if (contractAddresses.contains(it.target.toLowerCase(Locale.ENGLISH))) {
                        coin.symbol
                    } else {
                        it.target
                    }

                    CoinGeckoTickersResponse(base, target, it.market.name, it.market.identifier, it.last, it.volume)
                }

                val coinGeckoCoinInfo = CoinGeckoCoinInfo(
                    coinId = coin.id,
                    coinCode = coin.symbol,
                    title = coin.name,
                    description = coin.description["en"] ?: "",
                    links = links,
                    platforms = platforms,
                    tickers = tickers
                )

                val coinGeckoMarkets = coin.market_data.run {
                    val currencyCodeLowercase = currencyCode.toLowerCase(Locale.ENGLISH)

                    CoinGeckoCoinMarkets(
                        rate = current_price[currencyCodeLowercase] ?: BigDecimal.ZERO,
                        rateHigh24h = high_24h[currencyCodeLowercase] ?: BigDecimal.ZERO,
                        rateLow24h = low_24h[currencyCodeLowercase] ?: BigDecimal.ZERO,
                        marketCap = market_cap[currencyCodeLowercase] ?: BigDecimal.ZERO,
                        volume24h = total_volume[currencyCodeLowercase] ?: BigDecimal.ZERO,
                        circulatingSupply = circulating_supply ?: BigDecimal.ZERO,
                        totalSupply = total_supply ?: BigDecimal.ZERO,
                    )
                }

                val rateDiffsPeriod = rateDiffPeriods.map { period ->
                    val diffPeriod = when(period) {
                        TimePeriod.HOUR_1 -> coin.market_data.price_change_percentage_1h_in_currency
                        TimePeriod.HOUR_24 -> coin.market_data.price_change_percentage_24h_in_currency
                        TimePeriod.DAY_7 -> coin.market_data.price_change_percentage_7d_in_currency
                        TimePeriod.DAY_14 -> coin.market_data.price_change_percentage_14d_in_currency
                        TimePeriod.DAY_30 -> coin.market_data.price_change_percentage_30d_in_currency
                        TimePeriod.DAY_200 -> coin.market_data.price_change_percentage_200d_in_currency
                        TimePeriod.YEAR_1 -> coin.market_data.price_change_percentage_1y_in_currency
                        else -> coin.market_data.price_change_percentage_24h_in_currency
                    }

                    val rateDiffs = rateDiffCoinCodes.map { coinCode ->
                        coinCode to (diffPeriod[coinCode.toLowerCase(Locale.ENGLISH)] ?: BigDecimal.ZERO)
                    }.toMap()

                    period to rateDiffs
                }.toMap()


                CoinGeckoCoinMarketDetailsResponse(
                    coinGeckoCoinInfo,
                    coinGeckoMarkets,
                    rateDiffsPeriod
                )
            }

    }

    override fun getChartPointsAsync(chartPointKey: ChartInfoKey): Single<List<ChartPointEntity>> {
        val providerCoinId = getProviderCoinId(chartPointKey.coinType)
        return getCoinMarketCharts(providerCoinId, chartPointKey)
    }

    private fun getCoinMarketCharts(providerCoinId: String, chartPointKey: ChartInfoKey): Single<List<ChartPointEntity>> {
        val interval = if (chartPointKey.chartType.days >= 90) "daily" else null

        return coinGeckoService.coinMarketChart(
            providerCoinId,
            chartPointKey.currency,
            2 * chartPointKey.chartType.days,
            interval
        ).map {
            var nextTs = 0L
            val chartPointsCount = chartPointKey.chartType.interval * 2

            it.prices.mapIndexedNotNull { index, rateData ->
                val timestamp = rateData[0].toLong() / 1000

                if (timestamp >= nextTs || it.prices.size <= chartPointsCount) {
                    nextTs = timestamp + chartPointKey.chartType.seconds - 180
                    val rate = rateData[1]
                    val volume = if (chartPointKey.chartType.days >= 90) it.total_volumes[index][1] else BigDecimal.ZERO

                    ChartPointEntity(
                        chartPointKey.chartType,
                        chartPointKey.coinType,
                        chartPointKey.currency,
                        rate,
                        volume,
                        timestamp)

                } else {
                    null
                }
            }
        }
    }

    override fun getLatestRatesAsync(coinTypes: List<CoinType>, currencyCode: String): Single<List<LatestRateEntity>> {
        val providerCoinIds = providerCoinsManager.getProviderIds(coinTypes, this.provider).filterNotNull()
        return when {
            providerCoinIds.isEmpty() -> Single.just(listOf())
            else -> getLatestRates(providerCoinIds, currencyCode)
        }
    }

    private fun getLatestRates(coinIds: List<String>, currencyCode: String): Single<List<LatestRateEntity>> {
        return coinGeckoService.simplePrice(
            coinIds.joinToString(separator = ","),
            currencyCode,
            "false",
            "false",
            "true",
            "false",
        ).map { simplePrices ->
            val timestamp = System.currentTimeMillis() / 1000
            val currencyCodeLowercase = currencyCode.toLowerCase(Locale.ENGLISH)

            coinIds.mapNotNull { coinId ->
                val simplePrice = simplePrices[coinId] ?: return@mapNotNull null

                val rate = simplePrice[currencyCodeLowercase] ?: return@mapNotNull null
                val rateDiff24h = simplePrice[currencyCodeLowercase + "_24h_change"] ?: return@mapNotNull null

                val coinType = getCoinType(coinId) ?: return@mapNotNull null

                LatestRateEntity(
                    coinType = coinType,
                    currencyCode = currencyCode,
                    rateDiff24h = rateDiff24h,
                    rate = rate,
                    timestamp = timestamp
                )
            }
        }
    }

    override fun getHistoricalRateAsync(coinType: CoinType, currencyCode: String, timestamp: Long): Single<HistoricalRate> {
        val tsDiff = ((System.currentTimeMillis() / 1000) - timestamp) / 3600 //Diff in hours
        val fromTs = if (tsDiff < 24) timestamp - MINUTES_10_IN_SECONDS else timestamp - HOURS_2_IN_SECONDS
        val toTs = if (tsDiff < 24) timestamp + MINUTES_10_IN_SECONDS else timestamp + HOURS_2_IN_SECONDS

        return coinGeckoService.coinMarketChartRange(
            getProviderCoinId(coinType),
            currencyCode,
            fromTs,
            toTs
        ).map {
            val price = it.prices.minByOrNull {
                val timestampMillis = it[0].toLong()

                (timestampMillis / 1000L - timestamp).absoluteValue
            }!!.get(1)

            HistoricalRate(
                coinType = coinType,
                currencyCode = currencyCode,
                timestamp = timestamp,
                value = price
            )
        }
    }

}
