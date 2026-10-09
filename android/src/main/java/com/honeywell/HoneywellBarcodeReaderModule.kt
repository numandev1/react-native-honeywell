package com.honeywell

import android.os.Build
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.honeywell.aidc.AidcManager
import com.honeywell.aidc.BarcodeFailureEvent
import com.honeywell.aidc.BarcodeReadEvent
import com.honeywell.aidc.BarcodeReader

class HoneywellBarcodeReaderModule(reactContext: ReactApplicationContext) :
    NativeHoneywellSpec(reactContext), BarcodeReader.BarcodeListener {

    companion object {
        const val NAME = "HoneywellBarcodeReader"
        private const val TAG = "HoneywellBarcodeReader"
        private const val D = true

        private const val BARCODE_READ_SUCCESS = "barcodeReadSuccess"
        private const val BARCODE_READ_FAIL = "barcodeReadFail"
    }

    private var manager: AidcManager? = null
    private var reader: BarcodeReader? = null
    private val mReactContext: ReactApplicationContext = reactContext
    private val lock = Any()
    // Bumped on every start/stop so a late AidcManager.create callback knows it is stale
    private var generation = 0

    init {
        // Log that we're using the new architecture
        Log.i(TAG, "HoneywellBarcodeReader initialized with NEW architecture (TurboModule)")
    }

    override fun getName(): String = NAME

    private fun sendEvent(eventName: String, params: WritableMap?) {
        if (mReactContext.hasActiveReactInstance()) {
            if (D) Log.d(TAG, "Sending event: $eventName")
            mReactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }
    }

    override fun onBarcodeEvent(barcodeReadEvent: BarcodeReadEvent) {
        if (D) Log.d(TAG, "HoneywellBarcodeReader - Barcode scan read")
        val params = Arguments.createMap().apply {
            putString("data", barcodeReadEvent.barcodeData)
        }
        sendEvent(BARCODE_READ_SUCCESS, params)
    }

    override fun onFailureEvent(barcodeFailureEvent: BarcodeFailureEvent) {
        if (D) Log.d(TAG, "HoneywellBarcodeReader - Barcode scan failed")
        sendEvent(BARCODE_READ_FAIL, null)
    }

    override fun startReader(promise: Promise) {
        // Drop any previous session and invalidate pending create callbacks
        val gen = synchronized(lock) {
            releaseReaderLocked()
            ++generation
        }
        try {
            AidcManager.create(mReactContext) { aidcManager ->
                synchronized(lock) {
                    if (gen != generation) {
                        // stopReader/startReader ran while waiting for the scanner service
                        closeQuietly { aidcManager.close() }
                        promise.resolve(false)
                        return@create
                    }
                    manager = aidcManager
                    try {
                        // The SDK can throw (e.g. NPE in DcsJsonRpcHelper) when the
                        // scanner service is not ready; this runs on the main thread
                        val barcodeReader = aidcManager.createBarcodeReader()
                        if (barcodeReader == null) {
                            releaseReaderLocked()
                            promise.resolve(false)
                            return@create
                        }
                        reader = barcodeReader
                        barcodeReader.addBarcodeListener(this)
                        // Configure barcode reader properties using string constants
                        setReaderPropertyBoolean("DEC_EAN13_ENABLED", true)
                        setReaderPropertyBoolean("DEC_EAN8_CHECK_DIGIT_TRANSMIT", true)
                        setReaderPropertyBoolean("DEC_EAN13_CHECK_DIGIT_TRANSMIT", true)
                        setReaderPropertyBoolean("DEC_UPCA_ENABLE", true)
                        setReaderPropertyBoolean("DEC_UPCA_CHECK_DIGIT_TRANSMIT", true)
                        setReaderPropertyBoolean("DEC_UPCE_ENABLE", true)
                        setReaderPropertyBoolean("DEC_UPCE_CHECK_DIGIT_TRANSMIT", true)
                        setReaderPropertyBoolean("DEC_CODABAR_ENABLED", true)
                        setReaderPropertyBoolean("DEC_CODABAR_CHECK_DIGIT_MODE", true)
                        setReaderPropertyBoolean("DEC_I25_ENABLED", true)
                        setReaderPropertyBoolean("DEC_I25_CHECK_DIGIT_MODE", true)

                        barcodeReader.claim()
                        promise.resolve(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start barcode reader", e)
                        releaseReaderLocked()
                        promise.resolve(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AidcManager", e)
            promise.resolve(false)
        }
    }

    override fun stopReader(promise: Promise) {
        synchronized(lock) {
            generation++
            releaseReaderLocked()
        }
        promise.resolve(null)
    }

    private fun releaseReaderLocked() {
        reader?.let { barcodeReader ->
            closeQuietly { barcodeReader.removeBarcodeListener(this) }
            closeQuietly { barcodeReader.release() }
            closeQuietly { barcodeReader.close() }
        }
        reader = null
        manager?.let { aidcManager -> closeQuietly { aidcManager.close() } }
        manager = null
    }

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (D) Log.w(TAG, "Error releasing barcode reader: ${e.message}")
        }
    }

    override fun setReaderPropertyString(propName: String, value: String) {
        try {
            reader?.setProperty(propName, value)
        } catch (e: Exception) {
            if (D) Log.e(TAG, "Error setting reader property: ${e.message}")
        }
    }

    override fun setReaderPropertyNumber(propName: String, value: Double) {
        try {
            reader?.setProperty(propName, value.toInt())
        } catch (e: Exception) {
            if (D) Log.e(TAG, "Error setting reader property: ${e.message}")
        }
    }

    override fun setReaderPropertyBoolean(propName: String, value: Boolean) {
        try {
            reader?.setProperty(propName, value)
        } catch (e: Exception) {
            if (D) Log.e(TAG, "Error setting reader property: ${e.message}")
        }
    }

    override fun isCompatible(): Boolean =
        Build.BRAND.lowercase().contains("honeywell")

    override fun getConstants(): Map<String, Any> = mapOf(
        BARCODE_READ_SUCCESS to BARCODE_READ_SUCCESS,
        BARCODE_READ_FAIL to BARCODE_READ_FAIL,
        "BRAND" to Build.BRAND
    )

    override fun addListener(eventName: String) {
        // Required for RCTEventEmitter
    }

    override fun removeListeners(count: Double) {
        // Required for RCTEventEmitter
    }
}
