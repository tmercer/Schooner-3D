package com.supermercerbros.gameengine.objects;

import java.util.ArrayList;

import android.opengl.Matrix;
import android.util.Log;

import com.supermercerbros.gameengine.animation.Movement;
import com.supermercerbros.gameengine.engine.Engine;

/**
 * Represents a 3D mesh object.
 */
public class GameObject {
	public static final String TAG = "com.supermercerbros.gameengine.objects.GameObject";

	/**
	 * Contains the indices of the vertices for the elements (i.e. triangles) in
	 * this object.
	 */
	protected short[] indices;

	/**
	 * Contains the current object-space coordinates of the vertices used in
	 * this </code>GameObject</code>. Every three values represent one vertex.
	 */
	protected float[] verts;

	/**
	 * Contains either the UV coordinates (float pairs) or colors (three floats
	 * each) of the vertices in this <code>GameObject</code>.
	 * 
	 * @see <a href="http://en.wikipedia.org/wiki/UV_mapping">UV Mapping</a>
	 *      (Wikipedia)
	 */
	protected float[] mtl;

	/**
	 * Contains the normals of the vertices of this <code>GameObject</code>.
	 */
	protected float[] normals;

	/**
	 * The Metadata about this GameObject.
	 */
	public Metadata info;
	/**
	 * The model transformation matrix for this GameObject
	 */
	public float[] modelMatrix = new float[16];
	protected Movement motion;
	/**
	 * Contains the VBO offset at which this GameObject's data is loaded. This
	 * is used for multiple instances of the same primitive.
	 */
	protected int[] instanceLoaded = { -1 };

	private long lastDrawTime;
	/**
	 * Used by the Engine class when loading the GameObject into buffers.
	 */
	public int iOffset = -1;
	private boolean stationary;

	/**
	 * 
	 * @param verts
	 *            The object-space coordinates of the object's vertices. Every
	 *            three values represent one vertex (x-, y-, and z-coordinates).
	 * @param indices
	 *            The indices of the vertices for the triangles in this object.
	 * @param mtl
	 *            The UV texture coordinates of the triangles.
	 * @param normals
	 *            The coordinates of the normal vectors of the vertices.
	 * @param mtl
	 *            A Material object to use when for rendering
	 */
	public GameObject(float[] verts, short[] indices, float[] uvs,
			float[] normals, Material mtl) {
		Log.d(TAG, "Constructing GameObject...");
		this.verts = verts;
		this.indices = indices;
		this.mtl = uvs;
		this.normals = normals;
		info = new Metadata();
		info.size = indices.length;
		info.count = verts.length / 3;
		info.mtl = mtl;

		Matrix.setIdentityM(modelMatrix, 0);
		stationary = false;
	}

	private GameObject(float[] verts, short[] indices, float[] uvs,
			float[] normals, int[] instanceLoaded, Material mtl) {
		new GameObject(verts, indices, uvs, normals, mtl);
		
		this.instanceLoaded = instanceLoaded;
	}

	public ArrayList<GameObject> instance(int quantity) {
		if (quantity <= 0)
			return null;
		ArrayList<GameObject> instances = new ArrayList<GameObject>(quantity);
		for (int i = 0; i < quantity; i++) {
			instances.add(new GameObject(verts, indices, mtl, normals,
					instanceLoaded, info.mtl));
		}
		return instances;
	}

	/**
	 * @return the number of verts in this GameObject
	 */
	public int count() {
		return verts.length / 3;
	}

	/**
	 * This method is called to tell the object to recalculate it's vertices
	 * and/or transformation matrix for the given point in time, in
	 * milliseconds. If one wishes to do something with the object-space (local)
	 * vertices every frame, one should override this method in one's
	 * <code>GameObject
	 * </code> subclass.
	 * 
	 * @param time
	 *            The (estimated) time of the frame currently being calculated,
	 *            in milliseconds.
	 * 
	 * @see AnimatedMeshObject#draw(long)
	 */
	public void draw(long time) {
		if (stationary || motion == null) {
			lastDrawTime = time;
			return;
		}
		motion.getFrame(modelMatrix, 0, time);

		lastDrawTime = time;
	}

	/**
	 * @return the indices
	 */
	public short[] getIndices() {
		return indices;
	}

	/**
	 * @return the info
	 */
	public Metadata getInfo() {
		return info;
	}

	/**
	 * @return the last time given to {@link #draw(long)}.
	 */
	protected long getLastDrawTime() {
		return lastDrawTime;
	}

	/**
	 * @return the vector describing this object's direction. Changes in the
	 *         array obtained from this call will be reflected in the array
	 *         stored in this GameObject.
	 */
	public Movement getMotion() {
		return motion;
	}

	/**
	 * @return the normals
	 */
	public float[] getNormals() {
		return normals;
	}

	/**
	 * @return the mtl
	 */
	public float[] getUvs() {
		return mtl;
	}

	/**
	 * @return the verts
	 */
	public float[] getVerts() {
		return verts;
	}

	/**
	 * @return true if this GameObject has been marked for deletion.
	 * 
	 * @see #markForDeletion()
	 */
	public boolean isMarkedForDeletion() {
		return info.delete;
	}

	/**
	 * @return true if this object is stationary.
	 */
	public boolean isStationary() {
		return stationary;
	}

	/**
	 * Marks this GameObject for deletion. The Engine doesn't update this
	 * GameObject for rendering anymore, but it is not actually deleted from the
	 * Engine until {@link Engine#flushDeletedObjects()} is called. Should only
	 * be called from the Engine thread
	 */
	public void markForDeletion() {
		info.delete = true;
	}

	/**
	 * @param info
	 *            the info to set
	 */
	public void setInfo(Metadata info) {
		this.info = info;
	}

	/**
	 * @param stationary
	 *            <code>true</code> if this <code>GameObject</code> has no
	 *            motion. Note that <code>modelMatrix</code> can still be
	 *            modified, and will still affect the <code>GameObject</code>'s
	 *            position, but the object will not be translated or rotated
	 *            when <code>draw()</code> is called.
	 */
	public void setStationary(boolean stationary) {
		this.stationary = stationary;
	}

	/**
	 * Sets and starts the Movement that is used to animate this GameObject's
	 * location.
	 * 
	 * @param speed
	 *            the speed of the Movement
	 * @param time
	 *            the current time
	 * 
	 */
	public void startMotion(Movement motion, long time, float speed) {
		this.motion = motion;
		motion.start(time, modelMatrix, speed);
	}

}
