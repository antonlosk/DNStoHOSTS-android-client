package com.dnstohosts.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DNStoHOSTSTheme {
                // Using getExternalFilesDir(null) maps to:
                // /storage/emulated/0/Android/data/com.dnstohosts.app/files/
                val filesDir = getExternalFilesDir(null) ?: filesDir
                MainScreen(filesDir = filesDir)
            }
        }
    }
}

// --- Data Models ---

enum class ProcessState {
    IDLE,       // Grey bar
    RESOLVING,  // Blue indeterminate bar
    FINISHED    // Green full bar
}

data class AppSettings(
    val address: String = "dns.google",
    val port: Int = 443,
    val ipv4: Boolean = true,
    val ipv6: Boolean = false
)

// --- UI Theme (Android 16 Dark Style) ---

@Composable
fun DNStoHOSTSTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF6750A4), // Modern Purple/Blue tone
        onPrimary = Color.White,
        background = Color(0xFF121212), // Deep dark background
        surface = Color(0xFF1E1E1E), // Slightly lighter surface
        onSurface = Color(0xFFE6E1E5),
        error = Color(0xFFCF6679),
        surfaceVariant = Color(0xFF49454F)
    )

    MaterialTheme(
        colorScheme = darkColors,
        typography = Typography(),
        content = content
    )
}

// --- Main Screen ---

@Composable
fun MainScreen(filesDir: File) {
    var logs by remember { mutableStateOf(listOf<String>()) }
    var processState by remember { mutableStateOf(ProcessState.IDLE) }
    var job by remember { mutableStateOf<Job?>(null) }
    
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollToItem(logs.size - 1)
        }
    }

    fun appendLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs = logs + "[$time] $text"
    }

    fun clearLog() {
        logs = emptyList()
        processState = ProcessState.IDLE
    }

    fun stopProcess() {
        if (processState == ProcessState.RESOLVING) {
            appendLog("Stop requested, waiting for current operation to complete...")
            job?.cancel()
            processState = ProcessState.IDLE
            appendLog("Operation cancelled by user")
        }
    }

    fun startProcess() {
        if (processState == ProcessState.RESOLVING) return
        
        // Don't clear log automatically on start based on request, 
        // but state resets to RESOLVING (Blue)
        processState = ProcessState.RESOLVING
        
        job = scope.launch(Dispatchers.IO) {
            try {
                appendLog("Starting to resolve domains...")
                
                // 1. Read Settings
                appendLog("Reading settings.txt...")
                val settingsFile = File(filesDir, "settings.txt")
                if (!settingsFile.exists()) {
                    settingsFile.writeText("adress=dns.google\nport=443\nipv4=true\nipv6=false")
                }
                val settings = parseSettings(settingsFile)
                appendLog("DNS Server: ${settings.address}")
                appendLog("IPv4: ${settings.ipv4}, IPv6: ${settings.ipv6}")

                // 2. Read Input
                appendLog("Reading input.txt...")
                val inputFile = File(filesDir, "input.txt")
                if (!inputFile.exists()) {
                    inputFile.createNewFile()
                    appendLog("File input.txt not found, created empty file.")
                    processState = ProcessState.FINISHED
                    return@launch
                }

                val inputLines = inputFile.readLines()
                // Filter distinct domains for counting, but we process line by line to preserve structure
                val domainsToProcess = inputLines.filter { it.isNotBlank() && !it.trim().startsWith("#") }
                appendLog("Found ${domainsToProcess.size} domains to resolve")
                appendLog("----------------------------------------")

                val outputLines = mutableListOf<String>()
                
                // OkHttp Client setup
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                for (line in inputLines) {
                    if (!isActive) break // Coroutine cancelled

                    val trimmed = line.trim()
                    
                    // Handle empty lines
                    if (trimmed.isEmpty()) {
                        outputLines.add("")
                        continue
                    }

                    // Handle comments
                    if (trimmed.startsWith("#")) {
                        appendLog(trimmed) // Log the comment as requested
                        outputLines.add(trimmed)
                        continue
                    }

                    // Resolve Domain
                    val domain = trimmed
                    appendLog("Resolving: $domain")
                    
                    val resolvedIps = mutableListOf<String>()

                    if (settings.ipv4) {
                        val ips = executeBinaryDnsQuery(client, settings, domain, "A")
                        resolvedIps.addAll(ips)
                    }
                    if (settings.ipv6) {
                        val ips = executeBinaryDnsQuery(client, settings, domain, "AAAA")
                        resolvedIps.addAll(ips)
                    }

                    if (resolvedIps.isEmpty()) {
                        appendLog("  No records found for $domain")
                        outputLines.add("# No records found: $domain")
                    } else {
                        resolvedIps.forEach { ip ->
                            appendLog("  $ip $domain")
                            outputLines.add("$ip $domain")
                        }
                    }
                }

                if (isActive) {
                    appendLog("----------------------------------------")
                    appendLog("Writing output.txt...")
                    val outputFile = File(filesDir, "output.txt")
                    outputFile.writeText(outputLines.joinToString("\n"))
                    appendLog("Successfully wrote ${outputLines.size} lines to output.txt")
                    processState = ProcessState.FINISHED
                }

            } catch (e: CancellationException) {
                // Handled in stopProcess
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                processState = ProcessState.IDLE // Revert to grey on error? Or finish?
                // Let's keep it Finished or Idle.
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // --- Top Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { startProcess() },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Start")
                }
                
                Button(
                    onClick = { stopProcess() },
                    enabled = processState == ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Stop")
                }

                Button(
                    onClick = { clearLog() },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Clear Log")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Log Area ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { logLine ->
                        Text(
                            text = logLine,
                            color = Color(0xFFC0C0C0), // Light grey for text
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Progress Bar ---
            Box(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                when (processState) {
                    ProcessState.IDLE -> {
                        // Grey bar
                        LinearProgressIndicator(
                            progress = 0f,
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = Color.Gray,
                            color = Color.Gray
                        )
                    }
                    ProcessState.RESOLVING -> {
                        // Blue Indeterminate animation
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF448AFF), // Blue
                            trackColor = Color(0xFF333333)
                        )
                    }
                    ProcessState.FINISHED -> {
                        // Green Full bar
                        LinearProgressIndicator(
                            progress = 1f,
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4CAF50), // Green
                            trackColor = Color(0xFF333333)
                        )
                    }
                }
            }
        }
    }
}

// --- Logic Implementation ---

fun parseSettings(file: File): AppSettings {
    var address = "dns.google"
    var port = 443
    var ipv4 = true
    var ipv6 = false

    file.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val parts = trimmed.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim()
                
                when (key) {
                    "adress", "address" -> address = value
                    "port" -> port = value.toIntOrNull() ?: 443
                    "ipv4" -> ipv4 = value.toBoolean()
                    "ipv6" -> ipv6 = value.toBoolean()
                }
            }
        }
    }
    return AppSettings(address, port, ipv4, ipv6)
}

// --- Binary DNS Over HTTPS Implementation ---

fun executeBinaryDnsQuery(client: OkHttpClient, settings: AppSettings, domain: String, recordType: String): List<String> {
    val qType = if (recordType == "AAAA") 28 else 1
    val queryBytes = createDnsQueryPacket(domain, qType)
    
    // Construct URL with custom port
    val url = "https://${settings.address}:${settings.port}/dns-query"
    
    val requestBody = queryBytes.toRequestBody("application/dns-message".toMediaType())
    
    val request = Request.Builder()
        .url(url)
        .addHeader("Accept", "application/dns-message")
        .post(requestBody)
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val bodyBytes = response.body?.bytes() ?: return emptyList()
            parseDnsResponsePacket(bodyBytes, qType)
        }
    } catch (e: Exception) {
        // e.printStackTrace() // Debug only
        emptyList()
    }
}

/**
 * Creates a standard DNS Query Packet (Header + Question)
 */
fun createDnsQueryPacket(domain: String, type: Int): ByteArray {
    val baos = ByteArrayOutputStream()
    val dos = DataOutputStream(baos)

    // 1. Header (12 bytes)
    dos.writeShort(0x1234) // Transaction ID (Arbitrary)
    dos.writeShort(0x0100) // Flags: Standard Query, Recursion Desired (RD)
    dos.writeShort(1)      // Questions
    dos.writeShort(0)      // Answer RRs
    dos.writeShort(0)      // Authority RRs
    dos.writeShort(0)      // Additional RRs

    // 2. Question Section
    // QNAME: length-prefixed labels
    for (label in domain.split(".")) {
        val bytes = label.toByteArray(Charsets.UTF_8)
        dos.writeByte(bytes.size)
        dos.write(bytes)
    }
    dos.writeByte(0) // Root label (end of name)

    dos.writeShort(type) // QTYPE
    dos.writeShort(1)    // QCLASS (IN)

    return baos.toByteArray()
}

/**
 * Parses the DNS Response Packet to extract IP addresses
 */
fun parseDnsResponsePacket(data: ByteArray, reqType: Int): List<String> {
    val results = mutableListOf<String>()
    val dis = DataInputStream(ByteArrayInputStream(data))

    try {
        // --- Header ---
        val id = dis.readShort()
        val flags = dis.readShort()
        val qdCount = dis.readShort() // Questions
        val anCount = dis.readShort() // Answers
        val nsCount = dis.readShort()
        val arCount = dis.readShort()

        // --- Skip Questions ---
        for (i in 0 until qdCount) {
            skipName(dis) // Skip QNAME
            dis.readShort() // QTYPE
            dis.readShort() // QCLASS
        }

        // --- Parse Answers ---
        for (i in 0 until anCount) {
            skipName(dis) // NAME (usually a pointer 0xC0xx)
            
            val type = dis.readShort().toInt() and 0xFFFF
            val clazz = dis.readShort().toInt() and 0xFFFF
            val ttl = dis.readInt()
            val rdLength = dis.readShort().toInt() and 0xFFFF

            val rData = ByteArray(rdLength)
            dis.readFully(rData)

            if (type == reqType) {
                try {
                    val inetAddress = InetAddress.getByAddress(rData)
                    results.add(inetAddress.hostAddress ?: "")
                } catch (e: Exception) {
                    // Malformed IP data
                }
            }
        }
    } catch (e: EOFException) {
        // Packet ended prematurely
    } catch (e: Exception) {
        // Parsing error
    }

    return results
}

// Helper to skip DNS name (labels or pointers)
fun skipName(dis: DataInputStream) {
    while (true) {
        dis.mark(1)
        val len = dis.readByte().toInt() and 0xFF
        if (len == 0) return // End of name
        
        if ((len and 0xC0) == 0xC0) {
            // It's a pointer (2 bytes total), we read 1 byte, need 1 more
            dis.readByte() 
            return
        }
        
        // It's a label, skip 'len' bytes
        dis.skipBytes(len)
    }
}
