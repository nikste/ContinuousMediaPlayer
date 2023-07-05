package com.example.ContinuousMediaPlayer

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Semaphore

/**
 * Continuous speech synthesizer
 * We aim for following behaviour:
 * Once playback is requested, play all items in queue till null element
 * If no new items in queue and end (i.e. null) is not reached, wait for new items to be queued
 * Stop playing once the current item in the queue is null or clear is called
 * NOTE: you can freely add null element to anywhere in the queue, so be careful.
 * @constructor Create empty Continuous speech synthesizer
 */
class ContinuousMediaPlayer() : MediaPlayer() {
    val TAG = "CONTINUOUS_SPEECH_SYNTHESIZER"
    var semaphore = Semaphore(1)
    var delayInMS: Long = 500
    var queue: MutableList<ByteArrayMediaDataSource?> = mutableListOf<ByteArrayMediaDataSource?>()
    var currentItemIndex = 0
    var queueItemPlayedCompletely = false
    var playPressed = false
    var stopScheduled = false

    /**
     * Adds Item to queue
     * Internally calls (fake) text to speech API to convert to speech, then creates Audio source
     * if param is null, this will be added to the queue
     * @param inputString: String of text that should be synthesized or null (for end of queue)
     */
    suspend fun addToQueue(inputString: String?) {
        coroutineScope {
            launch {
                // Coroutine that will be canceled when the ViewModel is cleared.
                var queueItem: ByteArrayMediaDataSource? = null
                // in case the input is not null, we want to synthesize speech and parse it as media source
                if (inputString != null) {
                    Log.i(TAG, "adding to queue a non null string")
                    var synthesizedSpeech = synthesizeSpeech(inputString)
                    queueItem = convertStringToMediaDataSource(synthesizedSpeech)
                }
                semaphore.acquire()
                queue.add(queueItem)
                semaphore.release()
                Log.i(
                    TAG,
                    "added element to queue, now has " + queue.size + " elements to play!" + " currently playing " + currentItemIndex
                )
            }
        }
    }

    /**
     * Clear queue
     * clears queue and resets currentItemIndex
     */
    fun clearQueue(){
        semaphore.acquire()
        queue.clear()
        currentItemIndex = 0
        semaphore.release()
    }

    /**
     * Stop
     * stops playback, if playing then mediaPlayer playback will immidiately stop
     * all relevant members are reset to be able to call play again
     */
    override fun stop(){
        playPressed = false
        queueItemPlayedCompletely = true
        stopScheduled = true
        if(isPlaying()) {
            super.stop()
        }
    }

    /**
     * Play
     * starts playback if not allready playing
     */
    suspend fun play() {
        if(playPressed){
            Log.i(TAG, "is already playing")
            return
        }
        stopScheduled = false
        playPressed = true
        playContinuously()
    }

    /**
     * Play continuously
     * plays one item from the queue and waits for the next item to be enqueued if
     * no null (end of queue and stop signal) is reached yet
     */
    suspend fun playContinuously() {
        Log.i(TAG, "trying to play element " + currentItemIndex)
        queueItemPlayedCompletely = false
        reset()
        // no new item yet, wait till something has been added to the queue
        while (currentItemIndex > queue.size - 1) {
            Log.i(
                TAG,
                "waiting for next element to be queued .. " + currentItemIndex + " > " + queue.size + " - 1"
            )
            delay(delayInMS)
            if (stopScheduled){return}
        }

        if (queue[currentItemIndex] == null) {
            Log.i(TAG, "Encountered null as element, done playing queue!")
            stop()
            return
        }
        semaphore.acquire()
        Log.i(TAG, "using " + currentItemIndex + " from queue as data source")
        setDataSource(queue[currentItemIndex])
        semaphore.release()
        prepare()
        start()
        setOnCompletionListener {
            queueItemPlayedCompletely = true
        }
        // wait till completed
        // NOTE(nik): I dont think we can add a call to play() in OnCompletionListener,
        //            since that one is not suspended and we dont want to block the main thread?
        while (!queueItemPlayedCompletely) {
            Log.i(TAG, "waiting on completion of speech ..")
            delay(500)
            if(stopScheduled){return}
        }
        Log.i(TAG, "speech completed! rescheduling!")
        currentItemIndex += 1
        playContinuously()
    }

    /**
     * Convert string to media data source
     * converts base 64 string to byteArrayMediaDataSource that can be used in mediaplayer as source
     * @param base64string
     * @return converted ByteArrayMediaDataSource
     */
    fun convertStringToMediaDataSource(base64string: String): ByteArrayMediaDataSource {
        var data: ByteArray = Base64.decode(base64string, Base64.DEFAULT)
        return ByteArrayMediaDataSource(data)
    }

    /**
     * Synthesize speech
     * placeholder for some api call
     * @param inputString
     * @return
     */
    suspend fun synthesizeSpeech(inputString: String): String {
        // placeholder for post request to synthesize speech
        delay(2000)
        return inputString
    }

    /**
     * Byte array media data source
     * Holder of media data to be played by this player
     * @property data
     * @constructor Create empty Byte array media data source
     */
    class ByteArrayMediaDataSource(val data: ByteArray) : MediaDataSource() {
        override fun close() {
        }

        override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
            var length: Long = getSize()
            var sizeLocal = size
            if (position >= length) return -1; // EOF
            if (position + sizeLocal > length) // requested more than available
                sizeLocal = (length - position).toInt(); // set size to maximum size possible
            // at given position

            System.arraycopy(data, position.toInt(), buffer, offset, sizeLocal);
            return sizeLocal
        }

        override fun getSize(): Long {
            return data.size.toLong()
        }
    }
}