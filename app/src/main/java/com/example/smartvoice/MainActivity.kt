package com.example.smartvoice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var audioRecord: AudioRecord? = null
    private var isRecording by mutableStateOf(false)
    private lateinit var audioFile: File
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(onClick = {
                    if (isRecording) {
                        stopRecording()
                    } else {
                        startRecording()
                    }
                }) {
                    Text(if (isRecording) "Stop Recording" else "Start Recording")
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions, 200)
        }
    }

    private fun startRecording() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // Apply noise suppression if available
        audioRecord?.audioSessionId?.let { sessionId ->
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)
            } else {
                Toast.makeText(this, "Noise suppression not available", Toast.LENGTH_SHORT).show()
            }
        }

        audioRecord?.startRecording()
        isRecording = true

        // Buffer to store PCM data
        val outputStream = ByteArrayOutputStream()

        // Start a thread to record and send audio data
        Thread {
            try {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }
                Log.d("app_flow", "Recording finished!")

                sendAudioToServer(outputStream.toByteArray())

            } catch (e: IOException) {
                Log.d("app_flow", "Recording failed!")
                e.printStackTrace()
            }
        }.start()
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun sendAudioToServer(pcmData: ByteArray) {
        val url = "https://8000--main--kian-ws--kian--o1bn8ir3s2nsq.pit-1.try.coder.app/upload-audio/"

        // Create request body
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.pcm",
                pcmData.toRequestBody("audio/x-pcm".toMediaTypeOrNull(), 0)
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("app_flow", "Failed to send audio: ${e.message}")
                runOnUiThread {
                    Toast.makeText(applicationContext, "Failed to send audio", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("app_flow", "Server returned error: ${response.code}")
                    return
                }

                val responseBody = response.body?.bytes()
                if (responseBody != null) {
                    saveReceivedWavFile(responseBody)
                }
            }
        })
    }

    private fun saveReceivedWavFile(wavData: ByteArray) {
        val outputFile = File(getExternalFilesDir(null), "converted_audio.wav")
        try {
            outputFile.writeBytes(wavData)
            Log.d("app_flow", "WAV file saved: ${outputFile.absolutePath}")
            runOnUiThread {
                Toast.makeText(applicationContext, "Received and saved WAV file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            Log.e("app_flow", "Failed to save WAV file: ${e.message}")
        }
    }
}
