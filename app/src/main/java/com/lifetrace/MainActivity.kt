package com.lifetrace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

private val main = Handler(Looper.getMainLooper())
private fun bg(work: () -> Unit) { Thread { work() }.start() }
private fun ui(work: () -> Unit) { main.post(work) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(applicationContext)
        setContent { MaterialTheme { Root() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root() {
    val tabs = listOf("Status", "Check-in", "Insights", "Setup")
    var tab by remember { mutableStateOf(if (Config.isConfigured()) 0 else 3) }
    Scaffold(topBar = {
        Column {
            CenterAlignedTopAppBar(title = { Text("LifeTrace") })
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> StatusScreen()
                1 -> CheckinScreen()
                2 -> InsightsScreen()
                else -> SetupScreen()
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    var url by remember { mutableStateOf(Config.baseUrl) }
    var token by remember { mutableStateOf(Config.token) }
    var saved by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Server setup", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Point this app at your life-monitor hub. Stored only on this phone.", fontSize = 13.sp)
        OutlinedTextField(value = url, onValueChange = { url = it },
            label = { Text("Server URL (e.g. http://100.x.y.z:8799)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = token, onValueChange = { token = it },
            label = { Text("Ingest token") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            Config.save(url, token)
            saved = if (Config.isConfigured()) "saved -- go to Status and start tracking" else "both fields are required"
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (saved.isNotEmpty()) Text(saved, fontSize = 14.sp)
    }
}

@Composable
private fun StatusScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("idle") }
    val usage = remember(refresh) { Net.hasUsageAccess(ctx) }
    val loc = remember(refresh) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == 0
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Permissions", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        PermRow("Usage access (app screen-time)", usage) {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        PermRow("Location", loc) {
            permLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
        OutlinedButton(onClick = {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }) { Text("Allow location all the time") }

        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(onClick = {
                permLauncher.launch(arrayOf("android.permission.POST_NOTIFICATIONS"))
            }) { Text("Allow notifications") }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            OutlinedButton(onClick = {
                permLauncher.launch(arrayOf("android.permission.BLUETOOTH_CONNECT"))
            }) { Text("Allow Bluetooth (car / headphones)") }
        }
        OutlinedButton(onClick = {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + ctx.packageName)
            try { ctx.startActivity(i) } catch (e: Exception) {}
        }) { Text("Disable battery optimization") }

        Divider(Modifier.padding(vertical = 8.dp))
        Text("Tracking", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        Button(onClick = {
            SamplerService.start(ctx)
            status = "background tracking started"
        }, modifier = Modifier.fillMaxWidth()) { Text("Start background tracking") }

        OutlinedButton(onClick = {
            status = "uploading..."
            bg {
                val r = try { Net.collectAndUpload(ctx) } catch (e: Exception) { "error: ${e.message}" }
                ui { status = r }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Upload now") }

        Text(status, fontSize = 14.sp)
        Text("Server: ${Config.baseUrl}", fontSize = 12.sp)
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "[ok] " else "[--] ", fontWeight = FontWeight.Bold)
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        if (!granted) TextButton(onClick = onClick) { Text("Grant") }
    }
}

@Composable
private fun CheckinScreen() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf("tap Load") }
    var questions by remember { mutableStateOf<JSONArray?>(null) }
    var answered by remember { mutableStateOf(false) }
    val answers = remember { mutableStateMapOf<String, String>() }

    fun load() {
        state = "loading..."
        bg {
            try {
                val j = Net.fetchCheckin()
                ui {
                    answered = j.optBoolean("answered", false)
                    questions = j.optJSONArray("questions")
                    state = when {
                        answered -> "already answered today -- thanks"
                        j.optBoolean("pending", false) -> "answer tonight:"
                        else -> "no check-in set yet (agent runs ~21:15)"
                    }
                }
            } catch (e: Exception) { ui { state = "error: ${e.message}" } }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = { load() }) { Text("Load check-in") }
        Text(state, fontSize = 14.sp)

        val q = questions
        if (q != null && !answered) {
            for (i in 0 until q.length()) {
                val item = q.getJSONObject(i)
                val id = item.getString("id")
                val type = item.optString("type", "text")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.getString("q"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    when (type) {
                        "scale" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (n in 1..5) {
                                val sel = answers[id] == n.toString()
                                if (sel) Button(onClick = { answers[id] = n.toString() }) { Text("$n") }
                                else OutlinedButton(onClick = { answers[id] = n.toString() }) { Text("$n") }
                            }
                        }
                        "choice" -> {
                            val opts = item.optJSONArray("options") ?: JSONArray()
                            for (k in 0 until opts.length()) {
                                val opt = opts.getString(k)
                                val sel = answers[id] == opt
                                if (sel) Button(onClick = { answers[id] = opt }, modifier = Modifier.fillMaxWidth()) { Text(opt) }
                                else OutlinedButton(onClick = { answers[id] = opt }, modifier = Modifier.fillMaxWidth()) { Text(opt) }
                            }
                        }
                        else -> OutlinedTextField(
                            value = answers[id] ?: "",
                            onValueChange = { answers[id] = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("your answer") }
                        )
                    }
                }
            }

            Button(onClick = {
                state = "submitting..."
                bg {
                    try {
                        val ans = JSONObject()
                        for ((k, v) in answers) ans.put(k, v)
                        val mood = answers["mood"]?.toIntOrNull()
                        val energy = answers["energy"]?.toIntOrNull()
                        val focus = answers["focus"]?.toIntOrNull()
                        Net.submitCheckin(ans, mood, energy, focus)
                        ui { answered = true; state = "submitted -- thanks!" }
                    } catch (e: Exception) { ui { state = "error: ${e.message}" } }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Submit") }
        }
    }
}

@Composable
private fun InsightsScreen() {
    var state by remember { mutableStateOf("tap Load") }
    var items by remember { mutableStateOf<JSONArray?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = {
            state = "loading..."
            bg {
                try { val r = Net.fetchInsights(); ui { items = r; state = "" } }
                catch (e: Exception) { ui { state = "error: ${e.message}" } }
            }
        }) { Text("Load insights") }
        if (state.isNotEmpty()) Text(state, fontSize = 14.sp)
        val it = items
        if (it != null) {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("[${o.optString("kind")}] ${o.optString("date")}", fontSize = 12.sp)
                        Text(o.optString("title"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(o.optString("body"), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
