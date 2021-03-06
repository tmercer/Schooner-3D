package com.supermercerbros.gameengine.engine;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;

import android.util.Log;

import com.supermercerbros.gameengine.Schooner3D;
import com.supermercerbros.gameengine.objects.GameObject;
import com.supermercerbros.gameengine.objects.Metadata;
import com.supermercerbros.gameengine.util.DelayedRunnable;
import com.supermercerbros.gameengine.util.Toggle;

/**
 * Handles the interactions of game elements in the world space.
 * 
 * @version 1.0
 */
public class Engine extends Thread {
	private static final String TAG = "Engine";
	private DataPipe pipe;
	private RenderData out = new RenderData();
	private Camera cam;

	private int[] vboA;
	private short[] iboA;
	private float[] mmA;
	private float[] lightA;
	private float[] colorA;

	private boolean aBufs = true;

	private int[] vboB;
	private short[] iboB;
	private float[] mmB;
	private float[] lightB;
	private float[] colorB;

	/**
	 * To be used by subclasses of Engine. Contains the GameObjects currently in
	 * the Engine.
	 */
	protected List<GameObject> objects;
	private long time;

	// Be careful to always synchronize access of these fields:
	private volatile Toggle flush = new Toggle(false), paused = new Toggle(false);
	private volatile boolean started = false, ending = false;
	private boolean lightsChanged = false;
	/**
	 * Used for passing commands from the UI thread to the {@link Engine}
	 * thread. This <b>should not</b> be polled by any thread other than the
	 * Engine thread.
	 */
	ConcurrentLinkedQueue<Runnable> actions = new ConcurrentLinkedQueue<Runnable>();
	/**
	 * Used for passing delayed commands from the UI thread to the Engine
	 * thread.
	 */
	DelayQueue<DelayedRunnable> delayedActions = new DelayQueue<DelayedRunnable>();
	/**
	 * Used for passing new GameObjects from the UI thread to the {@link Engine}
	 * thread. This <b>should not</b> be polled by any thread other than the
	 * Engine thread.
	 */
	ConcurrentLinkedQueue<GameObject> newObjects = new ConcurrentLinkedQueue<GameObject>();
	/**
	 * Used for passing GameObjects to be deleted to the {@link Engine} thread.
	 * This <b>should not</b> be polled by any thread other than the Engine
	 * thread.
	 */
	ConcurrentLinkedQueue<GameObject> delObjects = new ConcurrentLinkedQueue<GameObject>();

	/**
	 * @param pipe
	 *            The DataPipe that this Engine will use to communicate with the
	 *            renderer and UI threads.
	 * @param cam
	 *            A pointer to the Camera object that will be used by the Game
	 *            Engine.
	 */
	public Engine(DataPipe pipe, Camera cam) {
		super("Schooner3D Engine thread");
		Log.d(TAG, "Constructing Engine...");
		this.pipe = pipe;
		this.cam = cam;
		this.objects = new LinkedList<GameObject>();
		this.vboA = new int[pipe.VBO_capacity / 4];
		this.vboB = new int[pipe.VBO_capacity / 4];
		this.iboA = new short[pipe.IBO_capacity / 2];
		this.iboB = new short[pipe.IBO_capacity / 2];
		this.mmA = new float[Schooner3D.maxObjects];
		this.mmB = new float[Schooner3D.maxObjects];
		this.lightA = new float[3];
		this.lightB = new float[3];
		this.colorA = new float[3];
		this.colorB = new float[3];
		Log.d(TAG, "Engine constructed.");
	}

	/**
	 * TODO Javadoc
	 * @param objects
	 */
	public void addAllObjects(Collection<GameObject> objects) {
		if (!started) {
			this.objects.addAll(objects);
		} else {
			newObjects.addAll(objects);
		}
	}

	/**
	 * TODO Javadoc
	 * @param object
	 */
	public void addObject(GameObject object) {
		if (!started) {
			objects.add(object);
		} else {
			newObjects.add(object);
		}
	}

	/**
	 * Runs a Runnable on the Engine thread
	 * 
	 * @param r
	 *            The Runnable to run on the Engine thread
	 */
	public void doRunnable(Runnable r) {
		actions.add(r);
	}

	/**
	 * Runs a {@link Runnable} on the Engine thread with a delay.
	 * 
	 * @param r
	 *            The Runnable to run on the Engine thread.
	 * @param delay
	 *            The amount by which to delay the run, in milliseconds
	 */
	public void doRunnable(Runnable r, long delay) {
		delayedActions.add(new DelayedRunnable(r, delay));
	}

	/**
	 * Terminates this Engine.
	 */
	public void end() {
		Log.d(TAG, "Engine state before end():" + getState().toString());
		ending = true;
		interrupt();
		Log.d(TAG, "Engine state after end():" + getState().toString());
		
	}

	/**
	 * Tells the Engine to actually delete all of its GameObjects that are
	 * marked for deletion
	 */
	public void flushDeletedObjects() {
		synchronized (flush) {
			flush.setState(true);
		}
	}

	/**
	 * Tells this Engine to pause processing. Used with {@link #resumeEngine()}.
	 */
	public void pause() {
		synchronized (paused) {
			paused.setState(true);
		}
	}

	/**
	 * Removes the given GameObject from the Engine.
	 * @param object
	 */
	public void removeObject(GameObject object) {
		if (!started) {
			objects.remove(object);
		} else {
			delObjects.add(object);
		}
	}

	/**
	 * Tells this Engine to resume processing.
	 */
	public void resumeEngine() {
		synchronized (paused) {
			paused.setState(false);
			paused.notify();
		}
	}

	/**
	 * Do not call this method. Call {@link #start()} to start the Engine.
	 * @see java.lang.Thread#run()
	 */
	@Override
	public synchronized void run() {
		if (Thread.currentThread() != this){
			throw new UnsupportedOperationException("Do not call Engine.run()");
		}
		while (!ending) {
			// Check for new GameObjects, GameObjects to delete, and actions to
			// perform.
			while (!actions.isEmpty())
				actions.poll().run();
			while (!newObjects.isEmpty())
				objects.add(newObjects.poll());
			while (!delObjects.isEmpty())
				delObject(delObjects.poll());

			DelayedRunnable d = delayedActions.poll();
			while (d != null) {
				d.r.run();
				d = delayedActions.poll();
			}

			synchronized (flush) {
				if (flush.getState()) {
					flush();
				}
			}

			doSpecialStuff(time);
			computeFrame();
			updatePipe();
			aBufs = !aBufs; // Swap aBufs
			
			if (ending){
				break;
			}
			synchronized (paused) {
				while (paused.getState()) {
					try {
						Log.d(TAG, "Waiting to unpause...");
						paused.wait();
					} catch (InterruptedException e) {
						Log.w(TAG, "Interrupted while waiting to unpause.");
						if (ending) {
							break;
						}
					}
				}
			}
		}

		Log.d(TAG, "end Engine");
	}

	/**
	 * Sets the directional light of the scene
	 * 
	 * @param x
	 *            The x-coordinate of the light vector
	 * @param y
	 *            The x-coordinate of the light vector
	 * @param z
	 *            The x-coordinate of the light vector
	 * @param r
	 *            The red value of the light's color
	 * @param g
	 *            The green value of the light's color
	 * @param b
	 *            The blue value of the light's color
	 */
	public void setLight(float x, float y, float z, float r, float g, float b) {
		synchronized (lightA) {
			if (aBufs) {
				lightA[0] = x;
				lightA[1] = y;
				lightA[2] = z;
				colorA[0] = r;
				colorA[1] = g;
				colorA[2] = b;
			} else {
				lightB[0] = x;
				lightB[1] = y;
				lightB[2] = z;
				colorB[0] = r;
				colorB[1] = g;
				colorB[2] = b;
			}
			lightsChanged = true;
		}
	}

	/**
	 * Use this method (<b>not</b> {@link #run()}) to start the Engine.
	 */
	@Override
	public void start() {
		started = true;
		super.start();			
	}

	/**
	 * This method is called every frame, before objects are redrawn. The
	 * default implementation does nothing; subclasses should override this if
	 * they wish to do anything special each frame.
	 * 
	 * @param time
	 *            The time of the current frame.
	 */
	protected void doSpecialStuff(long time) {

	}

	private void computeFrame() {
		// Collision detection goes here, whenever I need it.

		for (GameObject object : objects) {
			if (!object.isMarkedForDeletion()) {
				object.draw(time);
			}
		}

		cam.update(time);
	}

	/**
	 * Marks the given GameObject for deletion. 
	 * 
	 * @param object
	 *            The GameObject to remove from the Engine.
	 */
	private synchronized void delObject(GameObject object) {
		if (objects.contains(object)) {
			object.markForDeletion();
		}
	}

	private void flush() {
		for (int i = 0; i < objects.size(); i++) {
			if (objects.get(i).isMarkedForDeletion()) {
				objects.remove(i);
			}
		}
		flush.setState(false);
	}
	
	private int loadToIBO(short[] ibo, GameObject object, int offset,
			int vertexOffset) {
		object.iOffset = offset;
		if (object.isMarkedForDeletion())
			return 0;
		System.arraycopy(object.indices, 0, ibo, offset, object.info.size);
		return object.info.size;
	}

	private void updatePipe() {
		if (vboA == null)
			Log.e(TAG, "vboA == null");
		if (vboB == null)
			Log.e(TAG, "vboB == null");

		out.vbo = aBufs ? vboA : vboB;
		out.ibo = aBufs ? iboA : iboB;
		out.modelMatrices = aBufs ? mmA : mmB;
		out.ibo_updatePos = iboA.length;
		out.primitives = new Metadata[objects.size()];

		int vOffset = 0, iOffset = 0, vertexOffset = 0, matrixIndex = 0, i = 0;
		for (GameObject object : objects) {
			int bufferSize = object.info.mtl.loadObjectToVBO(object, out.vbo,
					vOffset);
			vOffset += bufferSize;

			iOffset += loadToIBO(out.ibo, object, iOffset, vertexOffset);

			vertexOffset += object.info.count;

			System.arraycopy(object.modelMatrix, 0, out.modelMatrices,
					matrixIndex++ * 16, 16);

			out.primitives[i++] = object.info;
		}

		cam.writeToArray(out.viewMatrix, 0);
		if (out.viewMatrix == null) {
			Log.e(TAG, "viewMatrix == null");
		}

		if (lightsChanged) {
			synchronized (lightA) {
				out.light = aBufs ? lightA : lightB;
				out.color = aBufs ? colorA : colorB;
			}
		}

		time = pipe.putData(time, out);
		synchronized (lightA) {
			if (aBufs) {
				System.arraycopy(lightA, 0, lightB, 0, 3);
				System.arraycopy(colorA, 0, colorB, 0, 3);
			} else {
				System.arraycopy(lightB, 0, lightA, 0, 3);
				System.arraycopy(colorB, 0, colorA, 0, 3);
			}
		}

	}
}
