/*
 * Copyright (C) 2010, 2011 Christopher Eby <kreed@kreed.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.kreed.vanilla;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Handle a provided MediaButton event and take the appropriate action in
 * PlaybackService.
 */
public class MediaButtonHandler {
	/**
	 * If another button event is received before this time in milliseconds
	 * expires, the event with be considered a double click.
	 */
	private static final int DOUBLE_CLICK_DELAY = 400;

	/**
	 * The current global instance of this class.
	 */
	private static MediaButtonHandler mInstance;
	/**
	 * Whether the headset controls should be used. 1 for yes, 0 for no, -1 for
	 * uninitialized.
	 */
	private static int mUseControls = -1;

	private Context mContext;
	/**
	 * Whether the phone is currently in a call. 1 for yes, 0 for no, -1 for
	 * uninitialized.
	 */
	private int mInCall = -1;
	/**
	 * Time of the last play/pause click. Used to detect double-clicks.
	 */
	private long mLastClickTime;

	private static AudioManager mAudioManager;
	private static Method mRegisterMediaButtonEventReceiver;
	private static Method mUnregisterMediaButtonEventReceiver;
	public static ComponentName mButtonReceiver;

	/**
	 * Retrieve the MediaButtonHandler singleton, creating it if necessary.
	 * Returns null if headset controls are not enabled.
	 */
	public static MediaButtonHandler getInstance(Context context)
	{
		if (useHeadsetControls(context)) {
			if (mInstance == null)
				mInstance = new MediaButtonHandler(context.getApplicationContext());
			return mInstance;
		}
		return null;
	}

	/**
	 * Construct a MediaButtonHandler.
	 */
	private MediaButtonHandler(Context context)
	{
		mContext = context;
		mAudioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
		mButtonReceiver = new ComponentName(context.getPackageName(), MediaButtonReceiver.class.getName());
		try {
			mRegisterMediaButtonEventReceiver = AudioManager.class.getMethod("registerMediaButtonEventReceiver", ComponentName.class);
			mUnregisterMediaButtonEventReceiver = AudioManager.class.getMethod("unregisterMediaButtonEventReceiver", ComponentName.class);
		} catch (NoSuchMethodException nsme) {
			// Older Android; just use receiver priority
		}
	}

	/**
	 * Reload the preference and enable/disable buttons as appropriate.
	 *
	 * @param context A context to use.
	 */
	public static void reloadPreference(Context context)
	{
		mUseControls = -1;
		if (useHeadsetControls(context)) {
			getInstance(context).registerMediaButton();
		} else {
			unregisterMediaButton();
		}
	}

	/**
	 * Return whether headset controls should be used, loading the preference
	 * if necessary.
	 *
	 * @param context A context to use.
	 */
	public static boolean useHeadsetControls(Context context)
	{
		if (mUseControls == -1) {
			SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
			mUseControls = settings.getBoolean("media_button", true) ? 1 : 0;
		}

		return mUseControls == 1;
	}

	/**
	 * Return whether the phone is currently in a call.
	 */
	private boolean isInCall()
	{
		if (mInCall == -1) {
			TelephonyManager manager = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
			mInCall = (byte)(manager.getCallState() == TelephonyManager.CALL_STATE_IDLE ? 0 : 1);
		}
		return mInCall == 1;
	}

	/**
	 * Set the cached value for whether the phone is in a call.
	 *
	 * @param value True if in a call, false otherwise.
	 */
	public void setInCall(boolean value)
	{
		mInCall = value ? 1 : 0;
	}

	/**
	 * Send the given action to the playback service.
	 *
	 * @param action One of the PlaybackService.ACTION_* actions.
	 */
	private void act(String action)
	{
		Intent intent = new Intent(mContext, PlaybackService.class);
		intent.setAction(action);
		mContext.startService(intent);
	}

	/**
	 * Process a media button key press.
	 */
	public boolean processKey(KeyEvent event)
	{
		if (event == null || isInCall() || !useHeadsetControls(mContext))
			return false;

		int action = event.getAction();

		switch (event.getKeyCode()) {
		case KeyEvent.KEYCODE_HEADSETHOOK:
		case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
			// single click: pause/resume.
			// double click: next track

			if (action == KeyEvent.ACTION_DOWN) {
				long time = SystemClock.uptimeMillis();
				if (time - mLastClickTime < DOUBLE_CLICK_DELAY)
					act(PlaybackService.ACTION_NEXT_SONG_AUTOPLAY);
				else
					act(PlaybackService.ACTION_TOGGLE_PLAYBACK);
				mLastClickTime = time;
			}
			break;
		case KeyEvent.KEYCODE_MEDIA_NEXT:
			if (action == KeyEvent.ACTION_DOWN)
				act(PlaybackService.ACTION_NEXT_SONG_AUTOPLAY);
			break;
		case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
			if (action == KeyEvent.ACTION_DOWN)
				act(PlaybackService.ACTION_PREVIOUS_SONG_AUTOPLAY);
			break;
		default:
			return false;
		}

		return true;
	}

	/**
	 * Process a MediaButton broadcast.
	 *
	 * @param intent The intent that was broadcast
	 * @return True if the intent was handled and the broadcast should be
	 * aborted.
	 */
	public boolean process(Intent intent)
	{
		KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
		return processKey(event);
	}

	/**
	 * Request focus on the media buttons from AudioManager.
	 */
	public void registerMediaButton()
	{
		assert(mUseControls == 1);
		if (mRegisterMediaButtonEventReceiver != null) {
			try {
				mRegisterMediaButtonEventReceiver.invoke(mAudioManager, mButtonReceiver);
			} catch (InvocationTargetException e) {
				Log.w("VanillaMusic", e);
			} catch (IllegalAccessException e) {
				Log.w("VanillaMusic", e);
			}
		}
	}

	/**
	 * Unregister the media buttons from AudioManager.
	 */
	public static void unregisterMediaButton()
	{
		if (mUnregisterMediaButtonEventReceiver != null) {
			try {
				mUnregisterMediaButtonEventReceiver.invoke(mAudioManager, mButtonReceiver);
			} catch (InvocationTargetException e) {
				Log.w("VanillaMusic", e);
			} catch (IllegalAccessException e) {
				Log.w("VanillaMusic", e);
			}
		}
	}
}
