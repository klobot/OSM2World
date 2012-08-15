package org.osm2world.console;

import static java.lang.Math.*;
import static javax.media.opengl.GL.*;
import static javax.media.opengl.GL2GL3.GL_FILL;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

import javax.imageio.ImageIO;
import javax.media.opengl.GL2;
import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLDrawableFactory;
import javax.media.opengl.GLPbuffer;
import javax.media.opengl.GLProfile;

import org.apache.commons.configuration.Configuration;
import org.osm2world.console.CLIArgumentsUtil.OutputMode;
import org.osm2world.core.ConversionFacade.Results;
import org.osm2world.core.target.TargetUtil;
import org.osm2world.core.target.common.lighting.GlobalLightingParameters;
import org.osm2world.core.target.common.rendering.Camera;
import org.osm2world.core.target.common.rendering.Projection;
import org.osm2world.core.target.jogl.JOGLTarget;
import org.osm2world.core.target.jogl.JOGLTextureManager;

import com.jogamp.opengl.util.awt.Screenshot;

public class ImageExporter {

	/**
	 * the width and height of the canvas used for rendering the exported image
	 * each must not exceed the canvas limit. If the requested image is larger,
	 * it will be rendered in multiple passes and combined afterwards.
	 * This is intended to avoid overwhelmingly large canvases
	 * (which would lead to crashes)
	 */
	private static final int DEFAULT_CANVAS_LIMIT = 1024;

	private static final String BG_COLOR_CONFIG_KEY = "backgroundColor";
	private static final String BG_IMAGE_CONFIG_KEY = "backgroundImage";
	private static final String CANVAS_LIMIT_CONFIG_KEY = "canvasLimit";
	
	private final Results results;
	
	private File backgroundImage;
	private JOGLTextureManager backgroundTextureManager;
	
	private GL2 gl;
	private GLPbuffer pBuffer;
	private final int pBufferSizeX;
	private final int pBufferSizeY;
	
	/** renderer with pre-calculated rendering ressources; can be null */
	private JOGLTarget bufferTarget;
	
	/**
	 * Creates an {@link ImageExporter} for later use.
	 * Also performs calculations that only need to be done once for a group
	 * of files, based on a {@link CLIArgumentsGroup}.
	 * 
	 * @param expectedGroup  group that should contain at least the arguments
	 *                       for the files that will later be requested.
	 *                       Basis for optimization preparations.
	 */
	public ImageExporter(Configuration config, Results results,
			CLIArgumentsGroup expectedGroup) {
		
		this.results = results;

		/* parse background color/image and other configuration options */
		
		float[] clearColor = {0f, 0f, 0f};
		
		if (config.containsKey(BG_COLOR_CONFIG_KEY)) {
			try {
				Color.decode(config.getString(BG_COLOR_CONFIG_KEY))
					.getColorComponents(clearColor);
			} catch (NumberFormatException e) {
				System.err.println("incorrect color value: "
						+ config.getString(BG_COLOR_CONFIG_KEY));
			}
		}
		
		if (config.containsKey(BG_IMAGE_CONFIG_KEY)) {
			String fileString = config.getString(BG_IMAGE_CONFIG_KEY);
			if (fileString != null) {
				backgroundImage = new File(fileString);
				if (!backgroundImage.exists()) {
					System.err.println("background image file doesn't exist: "
							+ backgroundImage);
					backgroundImage = null;
				}
			}
		}
		
		int canvasLimit = config.getInt(CANVAS_LIMIT_CONFIG_KEY, DEFAULT_CANVAS_LIMIT);
		
		/* find out what number and size of image file requests to expect */
		
		int expectedFileCalls = 0;
		int expectedMaxSizeX = 1;
		int expectedMaxSizeY = 1;
		
		for (CLIArguments args : expectedGroup.getCLIArgumentsList()) {
			
			for (File outputFile : args.getOutput()) {
				OutputMode outputMode = CLIArgumentsUtil.getOutputMode(outputFile);
				if (outputMode == OutputMode.PNG || outputMode == OutputMode.PPM) {
					expectedFileCalls = 1;
					expectedMaxSizeX = max(expectedMaxSizeX, args.getResolution().x);
					expectedMaxSizeY = max(expectedMaxSizeY, args.getResolution().y);
				}
			}
			
		}
		
		/* create GL canvas and set rendering parameters */

		GLProfile profile = GLProfile.getDefault();
		GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
		
		if (! factory.canCreateGLPbuffer(null)) {
			throw new Error("Cannot create GLPbuffer for OpenGL output!");
		}
		
		GLCapabilities cap = new GLCapabilities(profile);
		cap.setDoubleBuffered(false);
				
		pBufferSizeX = min(canvasLimit, expectedMaxSizeX);
		pBufferSizeY = min(canvasLimit, expectedMaxSizeY);
				
		pBuffer = factory.createGLPbuffer(null,
				cap, null, pBufferSizeX, pBufferSizeY, null);
		
		pBuffer.getContext().makeCurrent();
		gl = pBuffer.getGL().getGL2();
		
		gl.glFrontFace(GL_CCW);                  // use ccw polygons
					
		gl.glClearColor(clearColor[0], clearColor[1], clearColor[2], 1.0f);
        gl.glEnable (GL_DEPTH_TEST);             // z buffer
		gl.glCullFace(GL_BACK);
		gl.glEnable (GL_CULL_FACE);              // backface culling
		
		gl.glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        
        backgroundTextureManager = new JOGLTextureManager(gl);

		/* render map data into buffer if it needs to be rendered multiple times */
		
		boolean onlyOneRenderPass = (expectedFileCalls <= 1
				&& expectedMaxSizeX <= canvasLimit
				&& expectedMaxSizeY <= canvasLimit);
		
		if (config.getBoolean("forceUnbufferedPNGRendering", false)
				|| onlyOneRenderPass ) {
			
			bufferTarget = null;
			
		} else {
			
			bufferTarget = new JOGLTarget(gl, null, GlobalLightingParameters.DEFAULT);
			
			TargetUtil.renderWorldObjects(bufferTarget, results.getMapData());
			TargetUtil.renderObject(bufferTarget, results.getTerrain());
			
			bufferTarget.finish();
			
		}
		
	}
	
	protected void finalize() throws Throwable {
		freeResources();
	};

	/**
	 * manually frees resources that would otherwise remain used
	 * until the finalize call. It is no longer possible to use
	 * {@link #writeImageFile(File, OutputMode, int, int, Camera, Projection)}
	 * afterwards.
	 */
	public void freeResources() {

		if (backgroundTextureManager != null) {
			backgroundTextureManager.releaseAll();
			backgroundTextureManager = null;
		}
		
		if (bufferTarget != null) {
			bufferTarget.freeResources();
			bufferTarget = null;
		}
		
		if (pBuffer != null) {
			pBuffer.getContext().release();
	        pBuffer.destroy();
	        pBuffer = null;
	        gl = null;
		}
		
	}
	
	/**
	 * 
	 * @param outputFile
	 * @param outputMode   one of the image output modes
	 * @param x            horizontal resolution
	 * @param y            vertical resolution
	 * @param results
	 * @param camera
	 * @param projection
	 * @throws IOException
	 */
	public void writeImageFile(
			File outputFile, OutputMode outputMode,
			int x, int y,
			final Camera camera,
			final Projection projection) throws IOException {
		
		/* determine the number of "parts" to split the rendering in */
		
		int xParts = 1 + ((x-1) / pBufferSizeX);
		int yParts = 1 + ((y-1) / pBufferSizeY);
		
		/* create image (maybe in multiple parts) */
				
        BufferedImage image = new BufferedImage(x, y, BufferedImage.TYPE_INT_RGB);
        
		for (int xPart = 0; xPart < xParts; ++xPart) {
		for (int yPart = 0; yPart < yParts; ++yPart) {
			
			/* calculate start, end and size (in pixels)
			 * of the image part that will be rendered in this pass */
			
			int xStart = xPart * pBufferSizeX;
			int xEnd   = (xPart+1 < xParts) ? (xStart + (pBufferSizeX-1)) : (x-1);
			int xSize  = (xEnd - xStart) + 1;
			
			int yStart = yPart * pBufferSizeY;
			int yEnd   = (yPart+1 < yParts) ? (yStart + (pBufferSizeY-1)) : (y-1);
			int ySize  = (yEnd - yStart) + 1;
			
			/* configure rendering */
	        
	        gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	        
	       	if (backgroundImage != null) {
		       	JOGLTarget.drawBackgoundImage(gl, backgroundImage,
		       			xStart, yStart,
		       			xSize, ySize,
		       			backgroundTextureManager);
	       	}
	        
	        gl.glLoadIdentity();
	        
	        JOGLTarget.setProjectionMatricesForPart(gl, projection,
	        		xStart / (double)(x-1), xEnd / (double)(x-1),
	        		yStart / (double)(y-1), yEnd / (double)(y-1));
	        
	       	JOGLTarget.setCameraMatrices(gl, camera);
	        
	        /* render to pBuffer */
	        
	        if (bufferTarget != null) {
	        	
	        	bufferTarget.render(camera, projection);

	        } else {
	        	
		        JOGLTarget jogl = new JOGLTarget(gl, camera, GlobalLightingParameters.DEFAULT);
		        
				TargetUtil.renderWorldObjects(jogl, results.getMapData());
				TargetUtil.renderObject(jogl, results.getTerrain());
				
				jogl.finish();
				jogl.render(camera, projection);
				jogl.freeResources();
				
			}
	        
	        /* make screenshot and paste into the buffer
	         * that will contain the entire image*/

	        BufferedImage imagePart =
	        	Screenshot.readToBufferedImage(pBufferSizeX, pBufferSizeY);
	        
	        image.getGraphics().drawImage(imagePart,
	        		xStart, y-1-yEnd, xSize, ySize, null);
	        			
		}
		}
				
		/* write the entire image */
        
		switch (outputMode) {
			
			case PNG: ImageIO.write(image, "png", outputFile); break;
			case PPM: writePPMFile(image, outputFile); break;
			
			default: throw new IllegalArgumentException(
					"output mode not supported " + outputMode);
			
		}
		
	}
	
	private static void writePPMFile(BufferedImage image, File outputFile)
			throws IOException {
				
		FileOutputStream out = null;
		FileChannel fc = null;
		
		try {
			
			out = new FileOutputStream(outputFile);
			
			// write header
			
			Charset charSet = Charset.forName("US-ASCII");
			out.write("P6\n".getBytes(charSet));
			out.write(String.format("%d %d\n", image.getWidth(), image.getHeight())
					.getBytes(charSet));
			out.write("255\n".getBytes(charSet));
						
			// collect and write content

			ByteBuffer writeBuffer = ByteBuffer.allocate(
					3 * image.getWidth() * image.getHeight());
			
			DataBuffer imageDataBuffer = image.getRaster().getDataBuffer();
			int[] data = (((DataBufferInt)imageDataBuffer).getData());
			
			for (int value : data) {
				writeBuffer.put((byte)(value >>> 16));
				writeBuffer.put((byte)(value >>> 8));
				writeBuffer.put((byte)(value));
			}
			
			writeBuffer.position(0);
			
			fc = out.getChannel();
			fc.write(writeBuffer);
			
		} finally {
			
			if (fc != null) {
				fc.close();
			} else if(out != null) {
				out.close();
			}
			
		}

	}
	
}
