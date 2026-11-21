package fr.inertialnav.iau

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.inertialnav.iau.ihm.CalibrationScreen
import fr.inertialnav.iau.ihm.CompassScreen
import fr.inertialnav.iau.ihm.ControlScreen
import fr.inertialnav.iau.ihm.MainViewModel
import fr.inertialnav.iau.ihm.MapScreen
import fr.inertialnav.iau.ihm.MonitorScreen
import fr.inertialnav.iau.ui.theme.CataNavTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CataNavTheme {
                CataNavApp()
            }
        }
    }
}

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
)

@Composable
fun CataNavApp(vm: MainViewModel = viewModel()) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.CONTROL) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // Le service de type « location » exige la permission de localisation
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.startNavigation()
        } else {
            Toast.makeText(context, "La permission de localisation précise est requise", Toast.LENGTH_LONG).show()
        }
    }
    val requestStart = {
        val missing = REQUIRED_PERMISSIONS.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.startNavigation() else permissionLauncher.launch(missing.toTypedArray())
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = { Icon(painterResource(it.icon), contentDescription = it.label) },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it },
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val m = Modifier.padding(innerPadding)
            when (currentDestination) {
                AppDestinations.MONITOR -> MonitorScreen(vm, modifier = m)
                AppDestinations.MAP -> MapScreen(vm, modifier = m)
                AppDestinations.CONTROL -> ControlScreen(vm, onStartRequested = requestStart, modifier = m)
                AppDestinations.COMPASS -> CompassScreen(vm, modifier = m)
                AppDestinations.CALIBRATION -> CalibrationScreen(vm, modifier = m)
            }
        }
    }
}

/** Onglets, dans l'ordre d'affichage. L'écran ouvert au démarrage est [AppDestinations.CONTROL]. */
enum class AppDestinations(val label: String, val icon: Int) {
    MONITOR("Moniteur", R.drawable.ic_chart),
    MAP("Carte", R.drawable.ic_map),
    CONTROL("Commande", R.drawable.ic_tune),
    COMPASS("Boussole", R.drawable.ic_explore),
    CALIBRATION("Calibration", R.drawable.ic_calibration),
}
