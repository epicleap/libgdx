package com.badlogic.gdx.controllers.android;

import java.util.List;

import android.view.InputDevice;
import android.view.InputDevice.MotionRange;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnKeyListener;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidInput;
import com.badlogic.gdx.backends.android.AndroidInput.PauseResumeListener;
import com.badlogic.gdx.backends.android.AndroidInputThreePlus;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerListener;
import com.badlogic.gdx.controllers.ControllerManager;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;
import com.badlogic.gdx.utils.IntMap.Entry;
import com.badlogic.gdx.utils.Pool;

public class AndroidControllers implements PauseResumeListener, ControllerManager, OnKeyListener, OnGenericMotionListener {
	private final static String TAG = "AndroidControllers";
	private final IntMap<AndroidController> controllerMap = new IntMap<AndroidController>();
	private final Array<Controller> controllers = new Array<Controller>();
	private final Array<ControllerListener> listeners = new Array<ControllerListener>();
	private final Array<AndroidControllerEvent> eventQueue = new Array<AndroidControllerEvent>();
	private final Pool<AndroidControllerEvent> eventPool = new Pool<AndroidControllerEvent>() {
		@Override
		protected AndroidControllerEvent newObject () {
			return new AndroidControllerEvent();
		}
	};
	
	public AndroidControllers() {
		((AndroidInput)Gdx.input).addPauseResumeListener(this);
		gatherControllers(false);
		setupEventQueue();
		((AndroidInput)Gdx.input).addKeyListener(this);
		((AndroidInputThreePlus)Gdx.input).addGenericMotionListener(this);
		
		// use InputManager on Android +4.1 to receive (dis-)connect events
		if(Gdx.app.getVersion() >= 16) {
			try {
				String className = "com.badlogic.gdx.controllers.android.ControllerLifeCycleListener";
				Class.forName(className).getConstructor(AndroidControllers.class).newInstance(this);
			} catch(Exception e) {
				Gdx.app.log(TAG, "Couldn't register controller life-cycle listener");
			}
		}
	}
	
	private void setupEventQueue() {
		new Runnable() {
			@SuppressWarnings("synthetic-access")
			@Override
			public void run () {
				synchronized(eventQueue) {
					for(AndroidControllerEvent event: eventQueue) {
						switch(event.type) {
							case AndroidControllerEvent.CONNECTED:
								controllers.add(event.controller);
								for(ControllerListener listener: listeners) {
									listener.connected(event.controller);
								}
								break;
							case AndroidControllerEvent.DISCONNECTED:
								controllers.removeValue(event.controller, true);
								for(ControllerListener listener: listeners) {
									listener.disconnected(event.controller);
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									listener.disconnected(event.controller);
								}
								break;
							case AndroidControllerEvent.BUTTON_DOWN:
								event.controller.buttons.put(event.code, event.code);
								for(ControllerListener listener: listeners) {
									if(listener.buttonDown(event.controller, event.code)) break;
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									if(listener.buttonDown(event.controller, event.code)) break;
								}
								break;
							case AndroidControllerEvent.BUTTON_UP:
								event.controller.buttons.remove(event.code, 0);
								for(ControllerListener listener: listeners) {
									if(listener.buttonUp(event.controller, event.code)) break;
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									if(listener.buttonUp(event.controller, event.code)) break;
								}
								break;
							case AndroidControllerEvent.AXIS:
								event.controller.axes[event.code] = event.axisValue;
								for(ControllerListener listener: listeners) {
									if(listener.axisMoved(event.controller, event.code, event.axisValue)) break;
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									if(listener.axisMoved(event.controller, event.code, event.axisValue)) break;
								}
								break;
							default:
						}
					}
					eventPool.freeAll(eventQueue);
					eventQueue.clear();
				}
				Gdx.app.postRunnable(this);
			}
		}.run();
	}
	
	@Override
	public boolean onGenericMotion (View view, MotionEvent motionEvent) {
		if((motionEvent.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false;
		AndroidController controller = controllerMap.get(motionEvent.getDeviceId());
		if(controller != null) {
			synchronized(eventQueue) {
				final int historySize = motionEvent.getHistorySize();
				int axisIndex = 0;
            for (int axisId: controller.axesIds) {
            	float axisValue = motionEvent.getAxisValue(axisId);
            	if(controller.getAxis(axisIndex) == axisValue) {
            		Gdx.app.log(TAG, "skipped axis " + axisIndex + ", " + axisValue);
            		axisIndex++;
            		continue;
            	}
            	AndroidControllerEvent event = eventPool.obtain();
   				event.type = AndroidControllerEvent.AXIS;
   				event.controller = controller;
   				event.code = axisIndex;
   				event.axisValue = axisValue;
   				eventQueue.add(event);
   				axisIndex++;
            }
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean onKey (View view, int keyCode, KeyEvent keyEvent) {
		AndroidController controller = controllerMap.get(keyEvent.getDeviceId());
		if(controller != null) {
			if(keyEvent.getRepeatCount() == 0) {
				synchronized(eventQueue) {
					AndroidControllerEvent event = eventPool.obtain();
					event.controller = controller;
					if(keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
						event.type = AndroidControllerEvent.BUTTON_DOWN;
					} else {
						event.type = AndroidControllerEvent.BUTTON_UP;
					}
					event.code = keyCode;
					eventQueue.add(event);
				}
			}
			return true;
		} else {
			return false;
		}
	}
	
	private void gatherControllers(boolean sendEvent) {
		// gather all joysticks and gamepads, remove any disconnected ones
		IntMap<AndroidController> removedControllers = new IntMap<AndroidController>();
		removedControllers.putAll(controllerMap);
		
		for(int deviceId: InputDevice.getDeviceIds()) {
			InputDevice device = InputDevice.getDevice(deviceId);
			AndroidController controller = controllerMap.get(deviceId);
			if(controller != null) {
				removedControllers.remove(deviceId);
			} else {
				addController(deviceId, sendEvent);
			}
		}
		
		for(Entry<AndroidController> entry: removedControllers.entries()) {
			removeController(entry.key);
		}
	}
	
	protected void addController(int deviceId, boolean sendEvent) {
		InputDevice device = InputDevice.getDevice(deviceId);
		if(!isController(device)) return;
		String name = device.getName();
		AndroidController controller = new AndroidController(deviceId, name);
		controllerMap.put(deviceId, controller);
		if(sendEvent) {
			synchronized(eventQueue) {
				AndroidControllerEvent event = eventPool.obtain();
				event.type = AndroidControllerEvent.CONNECTED;
				event.controller = controller;
				eventQueue.add(event);
			}
		} else {
			controllers.add(controller);
		}
		Gdx.app.log(TAG, "added controller '" + name + "'");
	}
	
	protected void removeController(int deviceId) {
		AndroidController controller = controllerMap.remove(deviceId);
		if(controller != null) {
			synchronized(eventQueue) {
				AndroidControllerEvent event = eventPool.obtain();
				event.type = AndroidControllerEvent.DISCONNECTED;
				event.controller = controller;
				eventQueue.add(event);
			}
			Gdx.app.log(TAG, "removed controller '" + controller.getName() + "'");
		}
	}
	
	private boolean isController(InputDevice device) {
		return (device.getSources() & InputDevice.SOURCE_JOYSTICK) != 0;
	}

	@Override
	public Array<Controller> getControllers () {
		return controllers;
	}

	@Override
	public void addListener (ControllerListener listener) {
		synchronized(eventQueue) {
			listeners.add(listener);
		}
	}

	@Override
	public void removeListener (ControllerListener listener) {
		synchronized(eventQueue) {
			listeners.removeValue(listener, true);
		}
	}

	@Override
	public void pause () {
		Gdx.app.log(TAG, "controllers paused");
	}

	@Override
	public void resume () {
		gatherControllers(true);
		Gdx.app.log(TAG, "controllers resumed");		
	}
}
