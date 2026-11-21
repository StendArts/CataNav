package fr.inertialnav.iau.ihm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import fr.inertialnav.iau.CataNavApplication
import fr.inertialnav.iau.engine.CalibrationView
import fr.inertialnav.iau.engine.HybridationSettings
import fr.inertialnav.iau.engine.NavHistory
import fr.inertialnav.iau.engine.NavSnapshot
import fr.inertialnav.iau.service.NavigationService
import fr.inertialnav.iau.map.RasterNetworkMap
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = (application as CataNavApplication).engine

    val settings: StateFlow<HybridationSettings> = engine.settings
    val snapshot: StateFlow<NavSnapshot> = engine.snapshot
    val history: StateFlow<NavHistory> = engine.history
    val calibrationView: StateFlow<CalibrationView> = engine.calibrationView
    val networkMap: StateFlow<RasterNetworkMap?> = engine.networkMap

    fun startNavigation() = NavigationService.start(getApplication())
    fun stopNavigation() = NavigationService.stop(getApplication())
    fun resetFilter() = engine.resetFilter()

    fun setMag(enabled: Boolean) = engine.updateSettings { it.copy(magEnabled = enabled) }
    fun setGps(enabled: Boolean) = engine.updateSettings { it.copy(gpsEnabled = enabled) }
    fun setMap(enabled: Boolean) = engine.updateSettings { it.copy(mapEnabled = enabled) }
    fun setPdr(enabled: Boolean) = engine.updateSettings { it.copy(pdrEnabled = enabled) }
    fun setZupt(enabled: Boolean) = engine.updateSettings { it.copy(zuptEnabled = enabled) }
    fun setMock(enabled: Boolean) = engine.updateSettings { it.copy(mockEnabled = enabled) }

    fun startCalibration() = engine.startMagCalibration()
    fun finishCalibration() = engine.finishMagCalibration()
    fun cancelCalibration() = engine.cancelMagCalibration()
    fun clearCalibration() = engine.clearMagCalibration()
}
