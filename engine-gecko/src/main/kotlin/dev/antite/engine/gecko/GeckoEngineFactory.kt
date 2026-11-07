package dev.antite.engine.gecko

import android.content.Context
import dev.antite.engine.Engine
import dev.antite.engine.EngineFactory

class GeckoEngineFactory : EngineFactory {
    override fun create(context: Context): Engine = GeckoEngine.FACTORY.create(context)
}
