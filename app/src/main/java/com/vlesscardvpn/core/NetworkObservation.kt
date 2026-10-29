package com.vlesscardvpn.core

/** A result may be committed only to the same known physical path that started it. */
object NetworkObservation {
 fun accepts(start:NetworkHandoff.Snapshot?,current:NetworkHandoff.Snapshot?):Boolean =
  start!=null && start.handle.isNotBlank() && start.path.isNotBlank() && start==current
}

/** Keep mandatory Auto maintenance, but do not wake an explicitly disabled/unsupported mode. */
object BackgroundRunPolicy {
 fun enabled(automatic:Boolean,requested:Boolean,unsupported:Boolean)=!unsupported && (automatic||requested)
}
