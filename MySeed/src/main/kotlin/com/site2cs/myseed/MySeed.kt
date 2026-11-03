package com.site2cs.myseed

import com.site2cs.myseed.engine.SiteEngine
import com.site2cs.myseed.engine.SiteSpec

class MySeed : SiteEngine() {
    override val spec: SiteSpec = MySeedSpec
}
