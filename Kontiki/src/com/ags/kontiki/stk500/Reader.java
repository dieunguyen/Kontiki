package com.ags.kontiki.stk500;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeoutException;

import android.util.Log;

/**
 * This is an implementation of the IReader interface, used to control reading
 * from the InputStream. This implementation uses states inheriting from a base
 * state class with common functionality.
 * 
 * The reader attempts to stay responsive while reading, by only asking to read
 * maximum the amount of bytes already received (in BufferedInputStream or the
 * socket receiver buffer.
 * 
 * The reader runs the execute method of the current state for each iteration;
 * switching of states is handled by the states themselves. Calls of the
 * {@link IReader} methods are automatically performed on the current state.
 * 
 * See the {@link EReaderState EReaderState enum} documentation for details on
 * what each state does.
 */
public class Reader implements Runnable, IReader {
	private InputStream in;
	private BufferedInputStream bis;
	private volatile Exception lastException;
	private volatile IReaderState currentState;
	private volatile boolean doCompleteStop;
	private EnumMap<EReaderState, IReaderState> states;

	private Queue<IReaderState> eventQueue;
	private int result;

	/**
	 * Instance the reader and all the states utilized by it
	 * 
	 * @param input
	 *            Inputstream to read from
	 * @param logger
	 *            Logger interface for logging
	 */
	public Reader(InputStream input) {
		Log.i("Reader", "Reader constructor: Initializing...");
		if (input == null) {
			Log.d("Reader", "\n\n\ninput is null");
		}

		if (input == null) {
			throw new IllegalArgumentException("Reader.constructor: null as argument(s)");
		}

		eventQueue = new LinkedList<IReaderState>();

		in = input;
		// single largest expected return is 258
		bis = new BufferedInputStream(in, 1024);

		// instance the states
		states = new EnumMap<EReaderState, IReaderState>(EReaderState.class);
		IReaderState state;
		for (EReaderState eState : EReaderState.values()) {
			switch (eState) {
			case STOPPED: {
				state = new StoppedState(this, eState);
				break;
			}
			case STARTING: {
				state = new StartingState(this, eState);
				break;
			}
			case WAITING: {
				state = new WaitingState(this, eState);
				break;
			}
			case READING: {
				state = new ReadingState(this, eState);
				break;
			}
			case RESULT_READY: {
				state = new ResultReadyState(this, eState);
				break;
			}
			case TIMEOUT_OCCURRED: {
				state = new TimeoutOccurredState(this, eState);
				break;
			}
			case FAIL: {
				state = new FailureState(this, eState);
				break;
			}
			case STOPPING: {
				state = new StoppingState(this, eState);
				break;
			}
			default: {
				throw new IllegalStateException("Reader constructor: Unknown state:" + eState);
			}
			}

			states.put(eState, state);
		}
		currentState = states.get(EReaderState.STOPPED);
		Log.d("Reader", "Reader constructor: Done");
	}

	/**
	 * Schedule a state switch. Uses the {@link #addToEventQueue(IReaderState)
	 * addToEventQueue method} internally.
	 * 
	 * @param newState
	 *            The enum value corresponding to the state to switch to
	 */
	private void switchState(EReaderState newState) {
		addToEventQueue(states.get(newState));
	}

	/**
	 * Adds a state to the event queue. The current state will poll this queue
	 * and perform a state switch if it's not empty.
	 * 
	 * @param newState
	 *            The actual state instance
	 */
	private synchronized void addToEventQueue(IReaderState newState) {
		if (eventQueue.size() > 500) {
			Log.d("Reader", "addToEventQueue: Queue already full, has 500 states");
		}

		Log.d("Reader", "addToEventQueue: adding newState " + newState.getEnum() + " to queue");

		eventQueue.add(newState);

		Log.d("Reader", "addToEventQueue: states in queue after adding: " + eventQueue.size());

		synchronized (newState) {
			Log.d("Reader", "addToEventQueue: notifying all");
			notifyAll();
		}
	}

	/**
	 * Take a state from the queue
	 * 
	 * @return The state instance
	 */
	private synchronized IReaderState pollEventQueue() {
		IReaderState state = eventQueue.poll();
		if (state != null) {
			Log.d("Reader", "pollEventQueue: polling event " + state.getEnum() + " from queue");
		}
		return state;
	}

	/**
	 * Empties the event queue
	 */
	private synchronized void resetQueue() {
		eventQueue = new LinkedList<IReaderState>();
	}

	@Override
	public boolean wasCurrentStateActivated() {
		return currentState.hasStateBeenActivated();
	}

	@Override
	public void forget() {
		((IReader) currentState).forget();
	}

	@Override
	public EReaderState getState() {
		return currentState.getEnum();
	}

	@Override
	public int getResult() {
		return ((IReader) currentState).getResult();
	}

	@Override
	public int read(TimeoutValues timeout) throws TimeoutException, IOException {
		return ((IReader) currentState).read(timeout);
	}

	@Override
	public boolean stop() {
		return ((IReader) currentState).stop();
	}

	@Override
	public boolean start() {
		return ((IReader) currentState).start();
	}

	/**
	 * Use this method to completely stop execution of the reader. The reader
	 * has to be in a STOPPED state for this to work.
	 */
	public void requestCompleteStop() {
		if (currentState.getEnum() == EReaderState.STOPPED) {
			Log.d("Reader", "requestCompleteStop: setting doCompleteStop to true");
			doCompleteStop = true;
		}
		Log.d("Reader", "requestCompleteStop: can only shut down completely while " + "stopped. Current state: " + currentState.getEnum());
	}

	@Override
	public void run() {
		// Run until requested to stop
		while (!doCompleteStop) {
			currentState.execute();
		}

		Log.i("Reader", "Reader.run: Fully stopped (needs new Thread to restart)");
	}

	/**
	 * Sets the current state of the system. Should be called every time the
	 * state is changed.
	 * 
	 * @param currentState
	 *            the new current state
	 */
	public void setCurrentState(IReaderState currentState) {
		this.currentState = currentState;
	}

	/**
	 * Convenience class to limit required code in the actual states.
	 */
	abstract class BaseState implements IReaderState, IReader {

		private EReaderState eState;
		protected volatile boolean active;
		protected Reader reader;
		protected volatile boolean activated;
		protected boolean abort;

		public BaseState(Reader reader, EReaderState eState) {
			this.eState = eState;
			this.reader = reader;
			active = true;
			activated = false;
			abort = false;
		}

		@Override
		public boolean wasCurrentStateActivated() {
			return hasStateBeenActivated();
		}

		@Override
		public boolean hasStateBeenActivated() {
			return (currentState == this && activated);
		}

		@Override
		public void execute() {
			if (!activated && !abort) {
				activate();
			} else {
				// Check if it should switch state
				IReaderState nextState = pollEventQueue();
				// Change state
				if (nextState != null) {
					currentState = nextState;
					((BaseState) nextState).abort = false;
					abort = true;
					activated = false;
					return;
				}
			}
			if (!active) {
				synchronized (this) {
					try {
						Log.v("Reader", getEnum() + "(Base).execute: waiting...");
						wait(1000);
						active = true;
					} catch (InterruptedException e) {
						Log.v("Reader", getEnum() + "(Base).execute: woken up!");
						active = true;
					}
				}
			}
		}

		@Override
		public boolean isReadingAllowed() {
			return false;
		}

		@Override
		public EReaderState getState() {
			return this.getEnum();
		}

		@Override
		public int read(TimeoutValues timeout) throws TimeoutException, IOException {
			return RESULT_NOT_DONE;
		}

		@Override
		public int getResult() {
			return RESULT_NOT_DONE;
		}

		@Override
		public EReaderState getEnum() {
			return eState;
		}

		@Override
		public void forget() {
			throw new IllegalStateException(String.format("%s.forget: Only call when " + "timed out or waiting", eState));
		}

	}

	class StoppedState extends BaseState {

		public StoppedState(Reader reader, EReaderState eState) {
			super(reader, eState);
			active = false;
		}

		@Override
		public void execute() {
			super.execute();
			if (abort)
				return;
			active = false;
		}

		@Override
		public void activate() {
			Log.i("Reader", "StoppedState.activate: The reader has stopped");
			bis = null;
			activated = true;
			abort = false;
		}

		@Override
		public boolean stop() {
			Log.i("Reader", "StoppedState.stop: Already stopped");
			return true;
		}

		@Override
		public boolean start() {
			Log.d("Reader", getEnum() + " start: Starting...");
			switchState(EReaderState.STARTING);
			return true;
		}

	}

	class StartingState extends BaseState {

		public StartingState(Reader reader, EReaderState eState) {
			super(reader, eState);
			if (abort)
				return;
			active = true;
		}

		@Override
		public void activate() {
			Log.i("Reader", "StartingState.activate: Starting...");
			activated = true;
			active = true;
			abort = false;
			bis = new BufferedInputStream(in);
			switchState(EReaderState.WAITING);
		}

		@Override
		public void execute() {
			super.execute();
			if (abort)
				return;
		}

		@Override
		public boolean stop() {
			Log.e("Reader", "StartingState: Wait until it's running before attempting" + " to stop");
			return false;
		}

		@Override
		public boolean start() {
			Log.i("Reader", "StartingState.start: Already starting...");
			return true;
		}

	}

	class WaitingState extends BaseState {

		public WaitingState(Reader reader, EReaderState eState) {
			super(reader, eState);
		}

		@Override
		public void activate() {
			Log.d("reader", "WaitingState.activate: Ready to work");
			lastException = null;
			active = true;
			activated = true;
			abort = false;
		}

		@Override
		public boolean stop() {
			switchState(EReaderState.STOPPING);
			return true;
		}

		@Override
		public boolean start() {
			Log.i("Reader", "WaitingState.start: Already running...");
			return true;
		}

		@Override
		public void forget() {
			int toSkip;
			try {
				toSkip = bis.available();
				Log.d("Reader", "WaitingState.forget: Attempts to skip " + toSkip + " bytes...");
				long skipped = bis.skip(toSkip);
				Log.d("Reader", "WaitingState.forget: Skipped " + skipped + " bytes");
			} catch (IOException e) {
				Log.i("Reader", "WaitingState.forget: " + e.getMessage());
				lastException = e;
				switchState(EReaderState.FAIL);
			}
		}

		@Override
		public boolean isReadingAllowed() {
			return true;
		}

		@Override
		public int read(TimeoutValues timeout) throws TimeoutException, IOException {
			Log.i("Reader", getEnum() + " read: entered read method in Reader.java");
			Log.d("reader", "readtimeout: " + getEnum() + ",  " + currentState.getEnum());
			switchState(EReaderState.READING);
			while (true) {
				EReaderState s = currentState.getEnum();
				IReader state = (IReader) currentState;
				switch (s) {
				case RESULT_READY: {
					int res = state.getResult();
					Log.i("Reader", getEnum() + ".read: result: " + Hex.oneByteToHex((byte) res));
					return res;
				}
				case TIMEOUT_OCCURRED: {
					Log.d("read", "=========== TIMEOUT_OCCURRED");
					throw new TimeoutException("Reader.read: Reading timed out!");
				}
				case FAIL: {
					int res = state.getResult();
					if (res == RESULT_END_OF_STREAM) {
						return res;
					} else {
						if (lastException != null && lastException instanceof IOException) {
							throw ((IOException) lastException);
						}
					}
				}
				case READING: {
				} // intentional fall through
				case WAITING: {
					try {
						Thread.sleep(1);
					} catch (InterruptedException e) {
						Log.d("bug", e.toString());
						e.printStackTrace();
					}
					break;
				}
				case STOPPED: {
				} // fall through to stopping
				case STOPPING: {
					Log.w("reader", "Reader.read: Terminated by request while reading!");
					return RESULT_NOT_DONE;
				}
				default: {
					throw new IllegalArgumentException("Unexpected state " + s);
				}
				}
			}
		}
	}

	class ReadingState extends BaseState {
		private long readInitiated;

		public ReadingState(Reader reader, EReaderState eState) {
			super(reader, eState);
			readInitiated = -1;
		}

		@Override
		public void activate() {
			Log.d("Reader", "ReadingState.activate: Reading started...");
			synchronized (reader) {
				result = RESULT_NOT_DONE;
			}
			readInitiated = System.currentTimeMillis();
			active = true;
			activated = true;
			abort = false;
		}

		@Override
		public void execute() {
			super.execute();
			if (abort)
				return;
			try {
				int bytesInBuffer = -1;
				long now = System.currentTimeMillis();

				if (now - readInitiated > TimeoutValues.DEFAULT.getTimeout()) {
					switchState(EReaderState.TIMEOUT_OCCURRED);
					return;
				}

				// check if there are bytes in the buffer
				else {
					bytesInBuffer = (bis.available());
					if (bytesInBuffer > 0) {
						Log.d("Reader", getEnum() + ".execute: bytes in buffer: " + bytesInBuffer);
						int b = bis.read();
						// end of stream occurred, further operations will
						// trigger IOException
						if (b == RESULT_END_OF_STREAM) {
							Log.w("Reader", "ReadingState.execute: EndOfStream");
							synchronized (reader) {
								result = b;
							}
							switchState(EReaderState.FAIL);
						} else {
							// All good
							synchronized (reader) {
								result = b;
							}
							switchState(EReaderState.RESULT_READY);
						}
					}
				}
			} catch (IOException e) {
				Log.e("Reader", "ReadingState.execute: " + e.getMessage());
				lastException = e;
				switchState(EReaderState.FAIL);
			}
		}

		@Override
		public boolean stop() {
			Log.i("Reader", "ReadingState.stop: Stopping, this might take some time");
			switchState(EReaderState.STOPPING);
			return true;
		}

		@Override
		public boolean start() {
			Log.i("Reader", "ReadingState.start: Already running...");
			return true;
		}

	}

	class ResultReadyState extends BaseState {

		private boolean resultFetched = false;

		public ResultReadyState(Reader reader, EReaderState eState) {
			super(reader, eState);
		}

		@Override
		public void execute() {
			super.execute();
			if (abort)
				return;
			if (resultFetched) {
				Log.i("Reader", "ResultReadyState: execute: should switch state to WAITING.");
				switchState(EReaderState.WAITING);
			}
		}

		@Override
		public void activate() {
			resultFetched = false;
			Log.d("Reader", "ResultReadyState.activate: result arrived!");
			activated = true;
			abort = false;
		}

		@Override
		public int getResult() {
			int res = result;
			Log.d("Reader", getEnum() + " getResult: " + Hex.oneByteToHex((byte) res));
			synchronized (reader) {
				result = RESULT_NOT_DONE;
			}
			switchState(EReaderState.WAITING);
			synchronized (this) {
				resultFetched = true;
			}
			active = true;
			return res;
		}

		@Override
		public boolean stop() {
			switchState(EReaderState.STOPPING);
			return true;
		}

		@Override
		public boolean start() {
			Log.i("Reader", getEnum() + ".start: Already running...");
			return true;
		}

	}

	class TimeoutOccurredState extends BaseState {
		private volatile boolean readInProgress;
		private volatile boolean forgetInProgress;
		private volatile boolean receivedSomething;
		private int toSkip = -1;

		public TimeoutOccurredState(Reader reader, EReaderState eState) {
			super(reader, eState);
		}

		@Override
		public void execute() {
			super.execute();
			if (abort)
				return;
			int skippableBytes = 0;
			// only run while ready
			if (!forgetInProgress && !readInProgress && !receivedSomething) {
				try {

					// see estimate of skippable bytes (should be number of
					// buffered and
					// those in the socket receiver buffer)
					skippableBytes = bis.available();
					if (skippableBytes != toSkip) {
						Log.i("Reader", getEnum() + ".execute: " + skippableBytes + " possible to skip.");
						toSkip = skippableBytes;
					}
					if (skippableBytes > 0) {
						receivedSomething = true;
					}
				} catch (IOException e) {
					lastException = e;
					switchState(EReaderState.FAIL);
				}
			}
		}

		@Override
		public boolean isReadingAllowed() {
			// return (!readInProgress && !forgetInProgress);
			return false;
		}

		@Override
		public int read(TimeoutValues timeout) throws TimeoutException, IOException {
			if (!isReadingAllowed()) {
				throw new IllegalStateException("Reading not allowed while reading or " + "forgetting!");
			}
			return super.read(timeout);
		}

		@Override
		public int getResult() {
			if (receivedSomething) {
				switchState(EReaderState.WAITING);
				return TIMEOUT_BYTE_RECEIVED;
			}
			return RESULT_NOT_DONE;
		}

		@Override
		public void forget() {
			if (readInProgress || forgetInProgress) {
				throw new IllegalStateException("Can't start forget process while " + "already reading or forgetting");
			}
			forgetInProgress = true;
			int toSkip;
			try {
				toSkip = bis.available();
				Log.d("Reader", "TimeoutOccurred.forget: Attempts to skip " + toSkip + " bytes...");
				long skipped = bis.skip(toSkip);
				Log.d("Reader", "TimeoutOccurred.forget: Skipped " + skipped + " bytes");
			} catch (IOException e) {
				Log.i("Reader", "TimeoutOccurred.forget: " + e.getMessage());
				lastException = e;
				switchState(EReaderState.FAIL);
			} finally {
				forgetInProgress = false;
			}
		}

		@Override
		public void activate() {
			readInProgress = false;
			forgetInProgress = false;
			receivedSomething = false;
			abort = false;
			int avail = 0;
			toSkip = -1;

			try {
				avail = bis.available();
				if (avail > 0) {
					Log.w("Reader", getEnum() + ".activate: " + avail + "unread " + "bytes already in the buffer!");
					// ignore the byte(s)
					forget();
				}
			} catch (IOException e) {
				lastException = e;
				switchState(EReaderState.FAIL);
			}
			active = true;
			activated = true;
		}

		@Override
		public boolean stop() {
			Log.i("Reader", "TimeoutOccurredState.stop: Stopping... Might take a while " + "if blocking operations are in progress");
			switchState(EReaderState.STOPPING);
			return true;
		}

		@Override
		public boolean start() {
			Log.i("Reader", "TimeoutOccurredState.start: Already running");
			return true;
		}

	}

	class FailureState extends BaseState {

		public FailureState(Reader reader, EReaderState eState) {
			super(reader, eState);
		}

		@Override
		public void activate() {
			Log.e("Reader", getEnum() + ".activate: Reader failed!");
			activated = true;
			abort = false;
		}

		@Override
		public void execute() {
			super.execute();
			if (abort)
				return;
			active = false;
		}

		@Override
		public int getResult() {
			if (lastException != null) {
				if (lastException instanceof IOException) {
					// if an IOException was encountered outside of read().
					throw new RuntimeException(lastException);
				} else {
					throw new RuntimeException("Unexpected exception of type " + lastException.getClass(), lastException);
				}
			} else {
				throw new RuntimeException("An Unknown problem occured!");
			}
		}

		@Override
		public boolean stop() {
			switchState(EReaderState.STOPPING);
			Log.i("Reader", getEnum() + ".stop: Stopping...");
			return true;
		}

		@Override
		public boolean start() {
			Log.i("Reader", getEnum() + ".start: Already running, though currently" + " in a failure state.");
			return true;
		}

	}

	class StoppingState extends BaseState {

		public StoppingState(Reader reader, EReaderState eState) {
			super(reader, eState);
		}

		@Override
		public void activate() {
			resetQueue();
			Log.i("Reader", "StoppingState.activate: Shutdown in progress...");
			active = true;
			((BaseState) states.get(EReaderState.STOPPED)).abort = false;
			activated = true;
			abort = false;
		}

		@Override
		public void execute() {
			super.execute();
			if (abort)
				return;
			switchState(EReaderState.STOPPED);
		}

		@Override
		public boolean stop() {
			Log.i("Reader", "StoppingState.stop: Already stopping...");
			return true;
		}

		@Override
		public boolean start() {
			Log.e("Reader", getEnum() + ".start: Can't start during shutdown!");
			return false;
		}

	}

}
