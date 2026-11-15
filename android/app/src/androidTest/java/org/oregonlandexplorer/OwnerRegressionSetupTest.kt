package org.oregonlandexplorer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
class OwnerRegressionSetupTest {
    @Test fun restoreOriginalCorridorForExistingUiOracles() {
        val store=PackageStore(InstrumentationRegistry.getInstrumentation().targetContext)
        for(pack in store.installed()) if(pack.id.startsWith("marion-")) store.setEnabled(pack.id,false)
        for(pack in store.installed()) if(!pack.id.startsWith("marion-") && pack.kind in listOf("vector","parcels","legacy")) store.setEnabled(pack.id,true)
    }
}
