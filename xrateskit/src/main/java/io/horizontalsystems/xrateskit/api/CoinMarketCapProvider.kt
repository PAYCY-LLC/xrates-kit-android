package io.horizontalsystems.xrateskit.api

import io.horizontalsystems.xrateskit.core.Factory
import io.horizontalsystems.xrateskit.core.IMarketInfoProvider
import io.horizontalsystems.xrateskit.core.ITopMarketsProvider
import io.horizontalsystems.xrateskit.entities.*
import io.reactivex.Single
import java.lang.Exception
import java.util.logging.Logger

class CoinMarketCapProvider(
        private val factory: Factory,
        private val apiManager: ApiManager,
        private val baseUrl: String,
        private val apiKey: String,
        private val marketInfoProvider: IMarketInfoProvider
) : ITopMarketsProvider {

    private val logger = Logger.getLogger("CoinMarketCapProvider")

    override fun getTopMarketsAsync(itemsCount: Int, currency: String, rateDiffPeriod: TimePeriod): Single<List<TopMarket>> {
        return Single.create<CoinMarketCapTopMarketsResponse> { emitter ->
            try {
                val json = apiManager.getJson("$baseUrl/listings/latest?limit=$itemsCount", mapOf("X-CMC_PRO_API_KEY" to apiKey))

                emitter.onSuccess(CoinMarketCapTopMarketsResponse.parseData(json))
            } catch (ex: Exception) {
                emitter.onError(ex)
            }
        }.flatMap { response ->
            marketInfoProvider.getMarketInfo(response.values, currency)
                .map { marketInfos ->
                    response.values.mapNotNull { coin ->
                        marketInfos.firstOrNull {
                            it.coinCode.toUpperCase() == coin.code.toUpperCase()
                        }?.let { marketInfo ->
                            factory.createTopMarket(TopMarketCoin(coin.code, coin.title) , marketInfo)
                        }
                    }
                }
        }
    }
}