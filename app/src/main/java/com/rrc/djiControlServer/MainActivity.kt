package com.rrc.djiControlServer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatActivity

import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.*
import android.net.wifi.WifiManager
import android.os.*
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.virtualstick.*
import dji.common.gimbal.GimbalMode
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.media.DownloadListener
import dji.sdk.media.FetchMediaTask
import dji.sdk.media.MediaManager
import dji.sdk.media.order.MediaRequest
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import io.ktor.application.*
import io.ktor.gson.*
import io.ktor.routing.*
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

data class CommandCompleted(val completed: Boolean, val errorDescription: String?)

data class DroneState<T>(val state:T)

data class IMUState(val velX: Float, val velY: Float, val velZ: Float, val roll: Float, val pitch: Float, val yaw: Float)

data class VelocityCommand(val velX: Float, val velY: Float, val velZ: Float, val yawRate: Float)

class DJIControlException(message:String): Exception(message)

enum class VelocityProfile {
    CONSTANT, TRAPEZOIDAL, S_CURVE
}

enum class Direction {
    FORWARD, BACKWARD, LEFT, RIGHT, UP, DOWN, CLOCKWISE, COUNTER_CLOCKWISE
}

enum class ControlMode {
    POSITION, VELOCITY
}

class MainActivity : AppCompatActivity(), DJISDKManager.SDKManagerCallback {

    companion object {
        private const val PORT: Int = 8080
        private const val TAG = "MainActivity"
        private const val FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change"
        private lateinit var mHandler: Handler
        private val REQUIRED_PERMISSION_LIST: Array<String> = arrayOf(
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
        )
    }
    private lateinit var sdkManager: DJISDKManager

    private lateinit var ipText: TextView
    private lateinit var regText: TextView

    private lateinit var droneNameText: TextView
    private lateinit var batteryText: TextView

    private var drone: Aircraft? = null

    private var imuStates = mutableListOf<IMUState?>()
    private var imuStatePostRunnable: Runnable? = null
    private var readIMUState = false

    private var controlMode = ControlMode.POSITION

    // Position Control Mode Constant
    private var maxSpeed = 0.2f // 20cm/s
    private var maxAngularSpeed = 30.0f // 30 deg/s
    private var maxAcceleration = 0.1f // 10 cm/s^2
    private var maxAngularAcceleration = 15.0f // 15 deg/s^2
    private var maxJerk = 0.2f // 20 cm/s^3
    private var maxAngularJerk = 30.0f // 30 deg/s^3
    private var flightCommandInterval = 40L // 40 ms = 25Hz
    private var velocityProfile: VelocityProfile = VelocityProfile.CONSTANT

    // Velocity Control Mode Constants
    private var velocityModeXVel = 0f
    private var velocityModeYVel = 0f
    private var velocityModeZVel = 0f
    private var velocityModeYawVel = 0f
    private var followingVelocityCommands = false
    private var velocityControlRunnable: Runnable? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipText = findViewById(R.id.ipText)
        regText = findViewById(R.id.regText)
        droneNameText = findViewById(R.id.droneNameText)
        batteryText = findViewById(R.id.batteryText)

        displayIP()
        startServer()

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSION_LIST, 1)

        mHandler = Handler(Looper.getMainLooper())
        sdkManager = DJISDKManager.getInstance()
        sdkManager.setCallbackRunInUIThread(true)

        regText.text = getString(R.string.registering)

        sdkManager.registerApp(this, this)
    }

    // Embedded Server Functions
    private fun displayIP() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipString = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        ipText.text = getString(R.string.ip_format, ipString, PORT)
    }

    private fun startServer() {
        embeddedServer(Netty, PORT) {
            install(WebSockets)
            install(CallLogging)
            install(ContentNegotiation) {
                gson {  }
            }

            routing {
                meta()
                getState()
                setState()
                cameraControl()
                takeoffAndLandControl()
                velocityControl()
                throttleControl()
                yawControl()
                rollPitchControl()
            }
        }.start(wait = false)
    }

    // Server Routes
    private fun Route.meta() {
        get("/") {
            call.respondText ( text="Connected", contentType = ContentType.Text.Plain )
        }

        get("/reboot") {
            if(drone != null) {
                if(drone!!.flightController != null) {
                    val rebootError = suspendCoroutine<DJIError?> { cont ->
                        drone!!.flightController!!.reboot { error ->
                            cont.resume(error)
                        }
                    }

                    if(rebootError != null)
                        call.respond(CommandCompleted(false, "Reboot Error: " + rebootError.description))

                    call.respond(CommandCompleted(true, null))
                }
                else
                    call.respond(CommandCompleted(false, "Flight Controller Not available"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/startCollectingIMUState/{interval}") {
            if(drone != null) {

                try {
                    val imuReadInterval = if(call.parameters["interval"] != null)
                        call.parameters["interval"]!!.toLong()
                    else
                        1000L // Default once a second

                    val handler  = Handler(Looper.getMainLooper())

                    readIMUState = true

                    imuStatePostRunnable = object: Runnable {
                        override fun run() {
                            val currIMUState = drone?.flightController?.state
                            val currFilteredState = if(currIMUState != null)
                                IMUState(currIMUState.velocityX, currIMUState.velocityY, currIMUState.velocityZ,
                                    currIMUState.attitude.roll.toFloat(), currIMUState.attitude.pitch.toFloat(), currIMUState.attitude.yaw.toFloat())
                            else
                                null
                            imuStates.add(currFilteredState)
                            if(readIMUState)
                                handler.postDelayed(this, imuReadInterval)
                        }
                    }

                    handler.postDelayed(imuStatePostRunnable as Runnable, imuReadInterval)

                    call.respond(CommandCompleted(true, null))
                }
                catch(e:NumberFormatException) {
                    call.respond(CommandCompleted(false, "Invalid value for interval. Must be a positive integer."))
                }
                catch(e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/stopCollectingIMUState") {
            if(drone != null) {
                try {
                    readIMUState = false
                    val handler = Handler(Looper.getMainLooper())
                    handler.removeCallbacks(imuStatePostRunnable as Runnable)
                    call.respond(CommandCompleted(true, null))
                }
                catch(e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/getCurrentIMUState") {
            if(drone != null) {
                try {
                    val currIMUState = drone?.flightController?.state
                    val currFilteredState = if(currIMUState != null)
                        IMUState(currIMUState.velocityX, currIMUState.velocityY, currIMUState.velocityZ,
                            currIMUState.attitude.roll.toFloat(), currIMUState.attitude.pitch.toFloat(), currIMUState.attitude.yaw.toFloat())
                    else
                        null

                    if(currFilteredState == null)
                        call.respond(CommandCompleted(false, "Unable to fetch IMU data."))
                    else
                        call.respond(DroneState(currFilteredState))
                }
                catch (e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/getCollectedIMUStates") {
            try {
                call.respond(DroneState(imuStates))
            }
            catch(e: Exception) {
                call.respond(CommandCompleted(false, e.message))
            }
        }

        get("/clearCollectedIMUStates") {
            try {
                imuStates.clear()
                call.respond(CommandCompleted(true, null))
            }
            catch(e: Exception) {
                call.respond(CommandCompleted(false, e.message))
            }
        }
    }

    private fun Route.getState() {

        get("/isLandingProtectionEnabled") {
            if(drone != null) {
                val isLandingProtectionEnabled = suspendCoroutine { cont ->
                    drone?.flightController?.flightAssistant?.getLandingProtectionEnabled(object: CommonCallbacks.CompletionCallbackWith<Boolean> {
                        override fun onSuccess(p0: Boolean?) {
                            cont.resume(p0)
                        }

                        override fun onFailure(p0: DJIError?) {
                            cont.resume(null)
                        }

                    })
                }
                if(isLandingProtectionEnabled != null)
                    call.respond(DroneState<Boolean?>(isLandingProtectionEnabled))
                else
                    call.respond(CommandCompleted(false, "Unable to fetch state"))
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/isVirtualStickControlEnabled") {
            if(drone != null)
                call.respond(DroneState(drone?.flightController?.isVirtualStickControlModeAvailable))
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/getMaxSpeed") {
            call.respond(DroneState(maxSpeed))
        }

        get("/getMaxAngularSpeed") {
            call.respond(DroneState(maxAngularSpeed))
        }

        get("/getVelocityProfile") {
            val profileName = when(velocityProfile) {
                VelocityProfile.CONSTANT -> "CONSTANT"
                VelocityProfile.TRAPEZOIDAL -> "TRAPEZOIDAL"
                VelocityProfile.S_CURVE -> "S_CURVE"
            }
            call.respond(DroneState(profileName))
        }

        get("/getControlMode") {
            val controlModeName = when(controlMode) {
                ControlMode.POSITION -> "POSITION"
                ControlMode.VELOCITY -> "VELOCITY"
            }

            call.respond(DroneState(controlModeName))
        }

        get("/getHeading") {
            if(drone != null) {
                try{
                    call.respond(DroneState(drone?.flightController?.compass!!.heading))
                }
                catch(e: AssertionError) {
                    call.respond(CommandCompleted(false, "Cannot find compass component. Unable to fetch heading"))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/getAltitude") {
            if(drone == null)
                return@get call.respond(CommandCompleted(false, "Drone Not Available"))

            if(drone!!.flightController == null)
                return@get call.respond(CommandCompleted(false, "Flight Controller Unavailable"))

            if(drone!!.flightController!!.state.isUltrasonicBeingUsed)
                return@get call.respond(DroneState(drone!!.flightController!!.state.ultrasonicHeightInMeters))
            else
                return@get call.respond(CommandCompleted(false, "Ultrasonic Sensor Not Being Used Currently"))

        }

    }

    private fun Route.setState() {

        get("/enableLandingProtection") {
            if(drone != null) {
                val stateChangeError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.flightAssistant?.setLandingProtectionEnabled(true) { error ->
                        cont.resume(error)
                    }
                }

                call.respond(CommandCompleted(stateChangeError == null, stateChangeError?.description))
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/disableLandingProtection") {
            if(drone != null) {
                val stateChangeError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.flightAssistant?.setLandingProtectionEnabled(false) { error ->
                        cont.resume(error)
                    }
                }

                call.respond(CommandCompleted(stateChangeError == null, stateChangeError?.description))
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/setMaxSpeed/{speed}") {
            if(call.parameters["speed"] != null) {
                try {
                    val speed = call.parameters["speed"]!!.toFloat()

                    if(speed <= 0)
                        throw NumberFormatException("Speed must be a positive float")

                    maxSpeed = speed

                    call.respond(CommandCompleted(true, null))
                }
                catch(e: Exception){
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "No speed provided"))
        }

        get("/setMaxAngularSpeed/{speed}") {
            if(call.parameters["speed"] != null) {
                try {
                    val speed = call.parameters["speed"]!!.toFloat()

                    if(speed <= 0)
                        throw NumberFormatException("Speed must be a positive float")

                    maxAngularSpeed = speed

                    call.respond(CommandCompleted(true, null))
                }
                catch(e: Exception){
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "No speed provided"))
        }

        get("/setVelocityProfile/{profile}") {
            if(call.parameters["profile"] != null) {
                val profile = call.parameters["profile"].toString().uppercase()
                var validProfile = true
                when(profile) {
                    "CONSTANT" -> velocityProfile = VelocityProfile.CONSTANT
                    "TRAPEZOIDAL" -> velocityProfile = VelocityProfile.TRAPEZOIDAL
                    "S_CURVE" -> velocityProfile = VelocityProfile.S_CURVE
                    else -> validProfile = false
                }

                if(validProfile)
                    call.respond(CommandCompleted(true, null))
                else
                    call.respond(CommandCompleted(false, "Profile must be either 'CONSTANT', 'TRAPEZOIDAL' or 'S_CURVE'"))
            }
            else
                call.respond(CommandCompleted(false, "No profile provided"))
        }

        get("/setControlMode/{mode}") {
            if(call.parameters["mode"] != null) {

                try {
                    if(followingVelocityCommands)
                        throw DJIControlException("Cannot change control mode while velocity commands are being followed. First stop velocity control and then try again.")
                    val mode = call.parameters["mode"].toString().uppercase()
                    var validMode = true
                    when(mode) {
                        "POSITION" -> controlMode = ControlMode.POSITION
                        "VELOCITY" -> controlMode = ControlMode.VELOCITY
                        else -> validMode = false
                    }

                    if(validMode)
                        call.respond(CommandCompleted(true, null))
                    else
                        call.respond(CommandCompleted(false, "Profile must either be 'POSITION' or 'VELOCITY'"))
                }
                catch(e: DJIControlException){
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "No control mode provided"))
        }
    }

    private fun Route.cameraControl() {

        get("/captureShot") {

            // Availability Checks
            if(drone == null)
                return@get call.respond(CommandCompleted(false, "Drone not Available"))
            if(drone!!.camera == null)
                return@get call.respond(CommandCompleted(false, "Camera not Available"))
            if(drone!!.camera!!.mediaManager == null)
                return@get call.respond(CommandCompleted(false, "Media Manager Not Available"))
            if(!drone!!.camera!!.isFlatCameraModeSupported)
                return@get call.respond(CommandCompleted(false, "Only Flat-Camera Mode is supported"))

            // Get out of playback mode if in it
            suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.exitPlayback { error ->
                    cont.resume(error)
                }
            }

            val flatModeError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE) { error ->
                    cont.resume(error)
                }
            }

            if(flatModeError != null)
                return@get call.respond(CommandCompleted(false, "Error in setting flat mode: " + flatModeError.description))

            val singleShotError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.startShootPhoto { error ->
                    cont.resume(error)
                }
            }

            if(singleShotError != null)
                return@get call.respond(CommandCompleted(false, "Error in taking single shot" +  singleShotError.description))

            return@get call.respond(CommandCompleted(true, null))

        }

        get ("/startVideoRecording") {
            // Availability Checks
            if(drone == null)
                return@get call.respond(CommandCompleted(false, "Drone not Available"))
            if(drone!!.camera == null)
                return@get call.respond(CommandCompleted(false, "Camera not Available"))
            if(drone!!.camera!!.mediaManager == null)
                return@get call.respond(CommandCompleted(false, "Media Manager Not Available"))
            if(!drone!!.camera!!.isFlatCameraModeSupported)
                return@get call.respond(CommandCompleted(false, "Only Flat-Camera Mode is supported"))

            // Get out of playback mode if in it
            suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.exitPlayback { error ->
                    cont.resume(error)
                }
            }

            val flatModeError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.setFlatMode(SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL) { error ->
                    cont.resume(error)
                }
            }

            if(flatModeError != null)
                return@get call.respond(CommandCompleted(false, "Error in setting flat mode: " + flatModeError.description))

            val startVideoError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.startRecordVideo { error ->
                    cont.resume(error)
                }
            }

            if(startVideoError != null)
                return@get call.respond(CommandCompleted(false, "Error in starting video recording" +  startVideoError.description))

            return@get call.respond(CommandCompleted(true, null))
        }

        get("/stopVideoRecording") {
            // Availability Checks
            if(drone == null)
                return@get call.respond(CommandCompleted(false, "Drone not Available"))
            if(drone!!.camera == null)
                return@get call.respond(CommandCompleted(false, "Camera not Available"))
            if(drone!!.camera!!.mediaManager == null)
                return@get call.respond(CommandCompleted(false, "Media Manager Not Available"))
            if(!drone!!.camera!!.isFlatCameraModeSupported)
                return@get call.respond(CommandCompleted(false, "Only Flat-Camera Mode is supported"))

            val stopVideoError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.stopRecordVideo { error ->
                    cont.resume(error)
                }
            }

            if(stopVideoError != null)
                return@get call.respond(CommandCompleted(false, "Error in stopping video recording" +  stopVideoError.description))

            return@get call.respond(CommandCompleted(true, null))
        }

        get("/pitchGimbal/{angle}") {

            // Parse Angle
            val angle = try {
                -call.parameters["angle"]!!.toFloat()
            } catch (e: Exception) {
                0f
            }

            if(angle > 0 || angle < -90)
                return@get call.respond(CommandCompleted(false, "Pitch Angle Must be between 0 and 90 deg"))

            showToast("Made it here!")
            // Availability Checks
            if(drone == null)
                return@get call.respond(CommandCompleted(false, "Drone not Available"))
            if(drone!!.gimbal == null)
                return@get call.respond(CommandCompleted(false, "Gimbal not Available"))

            val gimbalModeError = suspendCoroutine<DJIError?> { cont ->
                drone!!.gimbal!!.setMode(GimbalMode.FPV) { error ->
                    cont.resume(error)
                }
            }

            if(gimbalModeError != null)
                return@get call.respond(CommandCompleted(false, "Gimbal Mode Error: " + gimbalModeError.description))


            val gimbalRotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(angle).yaw(Rotation.NO_ROTATION).roll(Rotation.NO_ROTATION).time(1.0).build()


            val gimbalRotationError = suspendCoroutine<DJIError?> { cont ->
                drone!!.gimbal!!.rotate(gimbalRotation) { error ->
                    cont.resume(error)
                }
            }

            if(gimbalRotationError != null)
                return@get call.respond(CommandCompleted(false, "Gimbal Rotation Error: " + gimbalRotationError.description))

            return@get call.respond(CommandCompleted(true, null))
        }

        get("/capturePanorama") {

            // Availability Checks
            if(drone == null)
                return@get call.respond(CommandCompleted(false, "Drone not Available"))
            if(drone!!.camera == null)
                return@get call.respond(CommandCompleted(false, "Camera not Available"))
            if(drone!!.camera!!.mediaManager == null)
                return@get call.respond(CommandCompleted(false, "Media Manager Not Available"))
            if(!drone!!.camera!!.isFlatCameraModeSupported)
                return@get call.respond(CommandCompleted(false, "Only Flat-Camera Mode is supported"))

            // Get out of playback mode if in it
            suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.exitPlayback { error ->
                    cont.resume(error)
                }
            }

            val flatModeError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_PANORAMA) { error ->
                    cont.resume(error)
                }
            }

            if(flatModeError != null)
                return@get call.respond(CommandCompleted(false, "Error in setting flat mode: " + flatModeError.description))

            val panoramaModeError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.setPhotoPanoramaMode(SettingsDefinitions.PhotoPanoramaMode.PANORAMA_MODE_3X1) { error ->
                    cont.resume(error)
                }
            }

            if(panoramaModeError != null)
                return@get call.respond(CommandCompleted(false, "Error in setting Panorama mode: " + panoramaModeError.description))

            val panoramaShotError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.startShootPhoto { error ->
                    cont.resume(error)
                }
            }

            if(panoramaShotError != null)
                return@get call.respond(CommandCompleted(false, "Error in taking panorama shot: " + panoramaShotError.description))

            return@get call.respond(CommandCompleted(true, null))

        }


        get("/fetchPreviewFromIndex/{n}") {

            // Parse Index
            val n = try {
                val parsedIdx = call.parameters["n"]!!.toInt()
                assert(parsedIdx >= 0)
                parsedIdx
            } catch (e: Exception) {
                0
            }

            // Availability Checks
            if(drone == null)
                return@get call.respond(CommandCompleted(false, "Drone not Available"))
            if(drone!!.camera == null)
                return@get call.respond(CommandCompleted(false, "Camera not Available"))
            if(drone!!.camera!!.mediaManager == null)
                return@get call.respond(CommandCompleted(false, "Media Manager Not Available"))
            if(!drone!!.camera!!.isFlatCameraModeSupported)
                return@get call.respond(CommandCompleted(false, "Only Flat-Camera Mode is supported"))

            var exitPlaybackError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.exitPlayback { error ->
                    cont.resume(error)
                }
            }

            if(exitPlaybackError != null)
                return@get call.respond(CommandCompleted(false, "Error in initial Playback mode exit: " + exitPlaybackError.description))

            // Refresh File List
            val refreshError = suspendCoroutine<DJIError?> { cont ->
                drone!!.camera!!.mediaManager!!.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD) { error ->
                    cont.resume(error)
                }
            }

            if(refreshError != null)
                return@get call.respond(CommandCompleted(false, "Error in Refreshing File List: " + refreshError.description))

            // Fetch Media Files and Sort
            val mediaFiles = drone!!.camera!!.mediaManager!!.sdCardFileListSnapshot
                ?: return@get call.respond(CommandCompleted(false, "Error in fetching File List Snapshot"))

            if (n >= mediaFiles.size)
                return@get call.respond(CommandCompleted(false, "Index $n greater than number of images available (${mediaFiles.size})"))

            mediaFiles.sortByDescending { it.timeCreated }

            // Get target file
            val targetFile = mediaFiles[n]

            // Fetch target file preview
            if(targetFile.preview == null)
            {
                val previewFetchError = suspendCoroutine<DJIError?> { cont ->
                    targetFile.fetchPreview { error ->
                        cont.resume(error)
                    }
                }

                if(previewFetchError != null)
                    return@get call.respond(CommandCompleted(false, "Error in fetching preview: " + previewFetchError.description + " Target File Name: " + targetFile.fileName))
            }

            // Convert image to base64 string
            val previewString = bitmapToString(targetFile.preview)

            // Exit Playback Mode
            exitPlaybackError = suspendCoroutine { cont ->
                drone!!.camera!!.exitPlayback { error ->
                    cont.resume(error)
                }
            }

            if(exitPlaybackError != null)
                return@get call.respond(CommandCompleted(false, "Error in exiting playback mode: " + exitPlaybackError.description))

            // Return Preview String
            return@get call.respond(DroneState(previewString))

        }

    }

    private fun Route.takeoffAndLandControl() {

        get("/takeoff") {
                if(drone != null) {
                    val takeoffError = suspendCoroutine<DJIError?> { cont ->
                        drone?.flightController?.startTakeoff { takeoffError ->
                            cont.resume(takeoffError)
                        }
                    }
                    call.respond(CommandCompleted(takeoffError == null, takeoffError?.description))
                }
            else {
                call.respond(CommandCompleted(false, "Drone Not Available"))
            }
        }

        get("/land") {
            if(drone != null){
                val descentError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.startLanding { descendError ->
                        cont.resume(descendError)
                    }
                }
                call.respond(CommandCompleted(descentError == null, descentError?.description))
            }
            else {
                call.respond(CommandCompleted(false, "Drone Not Available"))
            }
        }

        get("/confirmLanding") {
            if (drone != null) {
                val landingError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.confirmLanding { landingError ->
                        cont.resume(landingError)
                    }
                }
                call.respond(CommandCompleted(landingError == null, landingError?.description))
            } else {
                call.respond(CommandCompleted(false, "Drone not Available"))
            }
        }

    }

    private fun Route.velocityControl() {

        get("/startVelocityControl") {
            if(drone != null) {
                try {
                    if(controlMode == ControlMode.POSITION)
                        throw DJIControlException("Cannot use VELOCITY command in POSITION control mode")

                    setVirtualSticks(true)
                    setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                    val handler = Handler(Looper.getMainLooper())

                    followingVelocityCommands = true

                    velocityControlRunnable = object: Runnable {
                        override fun run() {
                            drone?.flightController?.sendVirtualStickFlightControlData(FlightControlData(velocityModeYVel, velocityModeXVel, velocityModeYawVel, velocityModeZVel)) {
                                if(followingVelocityCommands)
                                    handler.postDelayed(this, flightCommandInterval)
                            }
                        }
                    }

                    handler.postDelayed(velocityControlRunnable as Runnable, flightCommandInterval)

                    call.respond(CommandCompleted(true, null))
                }
                catch(e: DJIControlException) {
                    call.respond(CommandCompleted(false, e.message))
                }
                catch(e:Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/setVelocityCommand/{xVel}/{yVel}/{zVel}/{yawVel}") {
            if(drone != null) {
                try {
                    if(controlMode == ControlMode.POSITION)
                        throw DJIControlException("Cannot use VELOCITY command in POSITION control mode")
                    if(!followingVelocityCommands)
                        throw DJIControlException("Cannot set velocity commands before starting Velocity Control")
                    val xVel = call.parameters["xVel"]!!.toFloat()
                    val yVel = call.parameters["yVel"]!!.toFloat()
                    val zVel = call.parameters["zVel"]!!.toFloat()
                    val yawVel = call.parameters["yawVel"]!!.toFloat()

                    velocityModeXVel = xVel
                    velocityModeYVel = yVel
                    velocityModeZVel = zVel
                    velocityModeYawVel = yawVel

                    call.respond(CommandCompleted(true, null))
                }
                catch(e: NumberFormatException) {
                    call.respond(CommandCompleted(false, "Velocities must be valid floats."))
                }
                catch(e: DJIControlException) {
                    call.respond(CommandCompleted(false, e.message))
                }
                catch(e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/getCurrentVelocityCommand") {
            if(drone != null) {
                try {
                    if(controlMode == ControlMode.POSITION)
                        throw DJIControlException("Cannot use VELOCITY command in POSITION control mode")

                    call.respond(VelocityCommand(velocityModeXVel, velocityModeYVel, velocityModeZVel, velocityModeYawVel))
                }
                catch(e: DJIControlException) {
                    call.respond(CommandCompleted(false, e.message))
                }
                catch(e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/stopVelocityControl") {
            if(drone != null) {
                try {
                    if(controlMode == ControlMode.POSITION)
                        throw DJIControlException("Cannot use VELOCITY command in POSITION control mode")
                    setVirtualSticks(false)
                    followingVelocityCommands = false
                    velocityModeXVel = 0f
                    velocityModeYawVel = 0f
                    velocityModeZVel = 0f
                    velocityModeYawVel = 0f
                    val handler = Handler(Looper.getMainLooper())
                    handler.removeCallbacks(velocityControlRunnable as Runnable)
                    call.respond(CommandCompleted(true, null))
                }
                catch(e: DJIControlException) {
                    call.respond(CommandCompleted(false, e.message))
                }
                catch(e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

    }

    private fun Route.throttleControl() {

        get("/moveUp/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.UP)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))

        }

        get("/moveDown/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.DOWN)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))

        }

    }

    private fun Route.yawControl() {
        get("/rotateClockwise/{angle}") {
            if(drone != null) {
                if(call.parameters["angle"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val angle = getNumVal(call.parameters["angle"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(angle, Direction.CLOCKWISE)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/rotateCounterClockwise/{angle}") {
            if(drone != null) {
                if(call.parameters["angle"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val angle = getNumVal(call.parameters["angle"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(angle, Direction.COUNTER_CLOCKWISE)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }
    }

    private fun Route.rollPitchControl() {
        get("/moveForward/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                       createMotionPlanAndExecute(dist, Direction.FORWARD)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/moveBackward/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.BACKWARD)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/moveRight/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.RIGHT)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/moveLeft/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        if(controlMode == ControlMode.VELOCITY)
                            throw DJIControlException("Cannot use POSITION command in VELOCITY control mode")
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.LEFT)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }
    }

    // Motion planning functions
    private suspend fun createMotionPlanAndExecute(dist: Float, direction: Direction){
        val commands = when (velocityProfile) {
            VelocityProfile.CONSTANT -> generateConstantVelocityProfile(dist, direction)
            VelocityProfile.TRAPEZOIDAL -> generateTrapezoidalVelocityProfile(dist, direction)
            VelocityProfile.S_CURVE -> generateSCurveVelocityProfile(dist, direction)
        }
        for(command in commands) {
            drone?.flightController?.sendVirtualStickFlightControlData(command, null)
            delay(flightCommandInterval)
        }
    }

    private fun generateVelocitiesFromFunction(v: (Float)->Float, tTotal: Float, direction: Direction): MutableList<FlightControlData> {
        val tSteps = arange(0f, tTotal, flightCommandInterval/1000f)

        val velocities = mutableListOf<FlightControlData>()

        for(t in tSteps) {
            val command = when(direction) {
                Direction.FORWARD -> FlightControlData(0f, v(t), 0f, 0f)
                Direction.BACKWARD -> FlightControlData(0f, -v(t), 0f, 0f)
                Direction.RIGHT -> FlightControlData(v(t), 0f, 0f, 0f)
                Direction.LEFT -> FlightControlData(-v(t), 0f, 0f, 0f)
                Direction.UP -> FlightControlData(0f, 0f, 0f, v(t))
                Direction.DOWN -> FlightControlData(0f, 0f, 0f, -v(t))
                Direction.CLOCKWISE -> FlightControlData(0f, 0f, v(t), 0f)
                Direction.COUNTER_CLOCKWISE -> FlightControlData(0f, 0f, -v(t), 0f)
            }
            velocities.add(command)
        }
        velocities.add(FlightControlData(0f, 0f, 0f, 0f))
        return velocities
    }

    private fun generateConstantVelocityProfile(D: Float, direction: Direction) : MutableList<FlightControlData>{
        val v = if (direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularSpeed else maxSpeed
        val tTotal = D/v

        val vFn = fun(_:Float): Float {
            return v
        }

        return generateVelocitiesFromFunction(vFn, tTotal, direction)
    }

    private fun generateTrapezoidalVelocityProfile(D: Float, direction: Direction): MutableList<FlightControlData> {

        // Constraints
        val a = if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularAcceleration else maxAcceleration
        val vMax = min(D/2, if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularSpeed else maxSpeed )

        // Time taken at each phase
        val tp1 = vMax/a
        val tp2 = (a*D - (vMax.pow(2)))/(a*vMax)
        val tp3 = vMax/a

        // Total time taken
        val tTotal = tp1 + tp2 + tp3

        val vFn = fun(t:Float):Float {
            return if(t <= tp1)
                a*t
            else if(t <= tp1 + tp2)
                vMax
            else
                vMax - a*(t - tp1 - tp2)
        }

        return generateVelocitiesFromFunction(vFn, tTotal, direction)
    }

    private fun generateSCurveVelocityProfile(D:Float, direction: Direction): MutableList<FlightControlData> {

        // Constraints
        val j = if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularJerk else maxJerk
        val vMax = min(D/2, if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularSpeed else maxSpeed )
        val aMax = min(0.75f*vMax, if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularAcceleration else maxAcceleration )

        // Final velocity for each phase
        val vf1 = aMax.pow(2)/(2*j)
        val vf2 = vMax - aMax.pow(2)/(2*j)
        val vf3 = vMax
        val vf4 = vMax
        val vf5 = vMax - aMax.pow(2)/(2*j)
        val vf6 = aMax.pow(2)/(2*j)

        // Time taken for each phase
        val tp1 = aMax/j
        val tp2 = (vf2 - vf1)/aMax
        val tp3 = aMax/j
        val tp4 = ((aMax * j * D) - (vMax * aMax.pow(2)) - (j*vMax.pow(2)))/(j*aMax*vMax)
        val tp5 = aMax/j
        val tp6 = (vf5- vf6)/aMax
        val tp7 = aMax/j

        // Total time taken
        val tTotal = tp1 + tp2 + tp3 + tp4 + tp5 + tp6 + tp7

        val vFn = fun (t: Float): Float {
            if(t <= tp1)
            {
                return j * t.pow(2) / 2
            }
            else if(t <= tp1 + tp2){
                val tCurr = t - tp1
                return vf1 + (aMax * tCurr)
            }
            else if(t <= tp1 + tp2 + tp3) {
                val tCurr = t - tp1 - tp2
                return vf2 + aMax*tCurr - (j*tCurr.pow(2))/2
            }
            else if(t <= tp1 + tp2 + tp3 + tp4) {
                return vf3
            }
            else if(t <= tp1 + tp2 + tp3 + tp4 + tp5) {
                val tCurr = t - tp1 - tp2 - tp3 - tp4
                return vf4 - (j*tCurr.pow(2))/2
            }
            else if(t <= tp1 + tp2 + tp3 + tp4 + tp5 + tp6) {
                val tCurr = t - tp1 - tp2 - tp3 - tp4 - tp5
                return vf5 - aMax*tCurr
            }
            else{
                val tCurr = t - tp1 - tp2 - tp3 - tp4 - tp5 - tp6
                return vf6 - aMax*tCurr + (j*tCurr.pow(2))/2
            }
        }
        return generateVelocitiesFromFunction(vFn, tTotal, direction)
    }

    // Numeric Utility
    private fun getNumVal(numStr: String): Float {
        val num = numStr.toFloat()
        if (num <= 0)
            throw NumberFormatException("Non-Positive Float not allowed")
        return num
    }

    private fun arange(start:Float, stop:Float, step:Float) : MutableList<Float> {
        val a = mutableListOf<Float>()
        var i = start
        while(i < stop) {
            a.add(i)
            i += step
        }
        return a
    }

    // Misc Utility
    private fun bitmapToString(bitmap: Bitmap): String {
        val byteStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
        val bytes = byteStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // Flight controller state utility
    private suspend fun setVirtualSticks(enable: Boolean) {
        val virtualSticksError = suspendCoroutine<DJIError?> { cont ->
            drone?.flightController?.setVirtualStickModeEnabled(enable) {
                cont.resume(it)
            }
        }
        val action = if (enable) "Enable" else "Disable"

        if(virtualSticksError != null)
            throw DJIControlException("Cannot $action Virtual Sicks")
    }

    private fun getCurrentControlModes(): Triple<VerticalControlMode?, YawControlMode?, RollPitchControlMode?> {
        val vcm = drone?.flightController?.verticalControlMode
        val ycm = drone?.flightController?.yawControlMode
        val rpcm = drone?.flightController?.rollPitchControlMode

        return Triple(vcm, ycm, rpcm)
    }

    private fun setCurrentControlModes(desiredControlModes: Triple<VerticalControlMode?, YawControlMode?, RollPitchControlMode?>) {
        drone?.flightController?.verticalControlMode = desiredControlModes.first
        drone?.flightController?.yawControlMode = desiredControlModes.second
        drone?.flightController?.rollPitchControlMode = desiredControlModes.third
    }

    // UI Handling Functions
    private fun updateDroneDetails() {

        drone?.also { drone ->

            if(drone.isConnected) {
                drone.getName(object: CommonCallbacks.CompletionCallbackWith<String> {
                    override fun onSuccess(droneName: String?) {
                        droneNameText.text = droneName
                    }

                    override fun onFailure(error: DJIError?) {
                        droneNameText.text = getString(R.string.unknown_drone)
                        if(error != null)
                            showToast(error.description)
                    }
                })

                drone.battery.also { battery ->
                  battery.setStateCallback {
                      batteryText.text = getString(R.string.battery_percentage, it.chargeRemainingInPercent, "%")
                  }
                }
            }
            else {
                droneNameText.text = getString(R.string.drone_disconnected)
                batteryText.text = ""
                removeComponentCallbacks()
            }
        }?: run {
            droneNameText.text = getString(R.string.none_connected)
            batteryText.text = ""
        }
    }

    private fun removeComponentCallbacks() {
        drone?.battery?.setStateCallback(null)
    }

    // SDK Callback Functions
    override fun onRegister(error: DJIError?) {
        if(error == DJISDKError.REGISTRATION_SUCCESS) {
            regText.text = getString(R.string.registered, sdkManager.sdkVersion)
            Log.i(TAG, "SDK Registration Successful! Version: ${sdkManager.sdkVersion}")
            
            // Check for connected products immediately after registration
            val currentProduct = DJISDKManager.getInstance().product
            if (currentProduct != null) {
                Log.i(TAG, "Product already connected: ${currentProduct.model}")
            } else {
                Log.i(TAG, "No product connected yet. Waiting for connection...")
            }
        } else {
            regText.text = getString(R.string.register_failed)
            Log.e(TAG, "onRegister Failed: ${error?.description}")
        }
    }

    override fun onProductConnect(product: BaseProduct?) {
        Log.i(TAG, "onProductConnect called with product: ${product?.model}")
        drone = product as Aircraft?

        drone?.flightController?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

        updateDroneDetails()
        notifyStatusChanged()
    }

    override fun onProductChanged(product: BaseProduct?) {
        drone = product as Aircraft?

        drone?.flightController?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

        updateDroneDetails()
        notifyStatusChanged()
    }

    override fun onProductDisconnect() {
        Log.i(TAG, "onProductDisconnect called")
        removeComponentCallbacks()
        drone = null
        updateDroneDetails()
        notifyStatusChanged()
    }

    override fun onComponentChange(
        p0: BaseProduct.ComponentKey?,
        p1: BaseComponent?,
        p2: BaseComponent?
    ) {}

    override fun onInitProcess(p0: DJISDKInitEvent?, p1: Int) {}

    override fun onDatabaseDownloadProgress(p0: Long, p1: Long) {}

    // UI Utility Functions
    private fun notifyStatusChanged() {
        mHandler.removeCallbacks(updateRunnable)
        mHandler.postDelayed(updateRunnable, 500)
    }

    private val updateRunnable = Runnable {
        val intent = Intent(FLAG_CONNECTION_CHANGE)
        sendBroadcast(intent)
    }

    private fun showToast(text: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }
}