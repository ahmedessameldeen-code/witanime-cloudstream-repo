package com.site2cs.yam

import com.site2cs.yam.engine.SiteEngine
import com.site2cs.yam.engine.SiteSpec

class Yam : SiteEngine() {
    override val spec: SiteSpec = YamSpec
}
