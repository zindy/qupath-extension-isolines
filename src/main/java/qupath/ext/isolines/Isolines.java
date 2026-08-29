/*-
 * MIT License
 *
 * Copyright (c) 2026 - Egor Zindy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * ------------------------------------------------------------------------------
 * Transparency Notice regarding AI Generation:
 * This file was conceptually architected by a human and relies on QuPath's core 
 * APIs, but the boilerplate syntax and specific implementations were assisted 
 * by generative AI. It is provided strictly "AS IS".
 */

package qupath.ext.isolines;

import com.google.gson.JsonObject;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.heatmaps.DensityMaps;
import qupath.lib.analysis.heatmaps.ColorModels;
import qupath.lib.color.ColorMaps;
import qupath.lib.color.ColorMaps.ColorMap;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.GeometryTools;
import qupath.opencv.ml.pixel.PixelClassifierTools.CreateObjectOptions;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Generate colour-coded vector isoline annotations from a {@link DensityMaps} density map,
 * rather than displaying it as a dense raster/colour overlay.
 * <p>
 * This deliberately does not implement its own contour tracer. Instead it reuses
 * {@link DensityMaps#threshold(PathObjectHierarchy, ImageServer, Map, String, CreateObjectOptions...)},
 * calling it once per requested level with a distinct, uniquely-coloured {@link PathClass}.
 * Because the resulting thresholded regions are nested (region(&ge;v1) &sup; region(&ge;v2) for v1 &lt; v2),
 * the *boundary* of each region is exactly the isoline at that level. With annotation fill switched off
 * in the viewer, this gives you contour lines rather than filled bands -- useful when the underlying
 * image (e.g. an HDAB stain) needs to stay legible underneath.
 * <p>
 * <b>Restricting to a tissue region.</b> {@code DensityMaps.threshold(...)} (via
 * {@code PixelClassifierTools.createAnnotationsFromPixelClassifier}) follows the same convention as
 * the rest of QuPath's pixel-classifier-to-object machinery: if one or more objects are selected in the
 * hierarchy when it runs, object creation is restricted to those objects' ROIs and the results are added
 * as their children; with no selection, it falls back to the whole image. {@code DensityMaps.threshold}'s
 * own {@code DELETE_EXISTING} handling relies on exactly this (it branches on
 * {@code hierarchy.getSelectionModel().noSelection()}), and {@code DensityMaps.findHotspots} documents its
 * {@code hierarchy} parameter the same way. This class uses that convention to mask isolines to a tissue
 * annotation: pass it via {@code maskObjects} and the selection is set for the duration of the call, then
 * restored. Worth a quick sanity check on your own data before trusting it in a pipeline -- run it once on
 * a small region and confirm the isolines land as children of the tissue annotation and don't leak outside it.
 * <p>
 * Each call to {@code threshold(...)} does a full raster pass over the density server, so this is
 * O(nLevels) classification passes rather than one. Fine for a handful of levels; if you find yourself
 * wanting many levels routinely, it's worth writing a single-pass version that bands the density Mat
 * into integer labels in one go (compare {@code DensityMapDataOp}'s {@code PERCENT} branch, which
 * already does a similar rounding step) and traces each label boundary once.
 * <p>
 * <b>Extension note.</b> This class lives in the {@code qupath-extension-isolines} extension rather than
 * {@code qupath.lib.analysis.heatmaps} in QuPath core. It only depends on the public API of
 * {@link DensityMaps} and {@link ColorModels} (both core classes), so the move is otherwise a straight
 * package rename -- nothing here reaches into package-private core internals.
 *
 * @author Egor Zindy
 */
public class Isolines {

	private static final Logger logger = LoggerFactory.getLogger(Isolines.class);

	private Isolines() {}

	/**
	 * Colormap name and display range extracted from a density map's JSON, independent of
	 * {@link DensityMaps.DensityMapBuilder} (whose {@code colorModelBuilder} field has no public getter).
	 */
	public static class ColorModelInfo {

		private final String colorMapName;
		private final double minDisplay;
		private final double maxDisplay;

		private ColorModelInfo(String colorMapName, double minDisplay, double maxDisplay) {
			this.colorMapName = colorMapName;
			this.minDisplay = minDisplay;
			this.maxDisplay = maxDisplay;
		}

		public String getColorMapName() {
			return colorMapName;
		}

		public double getMinDisplay() {
			return minDisplay;
		}

		public double getMaxDisplay() {
			return maxDisplay;
		}

	}

	/**
	 * Read the colormap name and display range straight out of a density map's JSON file.
	 * This reparses the file independently of {@link DensityMaps#loadDensityMap(Path)}, since
	 * {@link DensityMaps.DensityMapBuilder} does not expose its {@code colorModelBuilder}.
	 * <p>
	 * Assumes the JSON was written with a {@code colorModelBuilder} of the single-channel
	 * (band + optional alpha band) form serialized by {@link ColorModels}. If the density map
	 * was created without calling {@code DensityMapBuilder#colorModel(...)}, this will fail --
	 * there is nothing to extract.
	 *
	 * @param jsonPath path to the density map JSON (as produced when a density map is saved from the GUI)
	 * @return the extracted colormap name and display range
	 * @throws IOException if the file can't be read, or doesn't contain the expected structure
	 */
	public static ColorModelInfo readColorModelInfo(Path jsonPath) throws IOException {
		try (var reader = Files.newBufferedReader(jsonPath)) {
			var json = GsonTools.getInstance().fromJson(reader, JsonObject.class);
			var colorModelBuilder = json.getAsJsonObject("colorModelBuilder");
			if (colorModelBuilder == null)
				throw new IOException("No 'colorModelBuilder' entry in " + jsonPath
						+ " -- was a ColorModel set when this density map was created?");
			var band = colorModelBuilder.getAsJsonObject("band");
			if (band == null)
				throw new IOException("No 'band' entry under 'colorModelBuilder' in " + jsonPath);
			String colorMapName = band.get("colorMapName").getAsString();
			double minDisplay = band.get("minDisplay").getAsDouble();
			double maxDisplay = band.get("maxDisplay").getAsDouble();
			return new ColorModelInfo(colorMapName, minDisplay, maxDisplay);
		}
	}

	/**
	 * Compute {@code nLevels} evenly-spaced isoline levels strictly between {@code min} and {@code max}
	 * (endpoints excluded, since a region &ge; min or &ge; max is usually not an interesting contour).
	 *
	 * @param min lower bound of the display range
	 * @param max upper bound of the display range
	 * @param nLevels number of levels to generate; must be &gt; 0
	 * @return the levels, ascending
	 */
	public static double[] computeLevels(double min, double max, int nLevels) {
		if (nLevels <= 0)
			throw new IllegalArgumentException("nLevels must be > 0, was " + nLevels);
		if (max <= min)
			throw new IllegalArgumentException("max (" + max + ") must be > min (" + min + ")");
		double[] levels = new double[nLevels];
		for (int i = 0; i < nLevels; i++)
			levels[i] = min + (max - min) * (i + 1) / (double) (nLevels + 1);
		return levels;
	}

	/**
	 * Create isoline annotations for a density map at the given levels, across the whole image
	 * (no tissue/region mask). See {@link #createIsolines(PathObjectHierarchy, ImageServer, int, double[],
	 * ColorMap, double, double, Collection, CreateObjectOptions...)} to restrict to one or more parent objects.
	 *
	 * @param hierarchy hierarchy to add annotations to
	 * @param densityServer the density map (see {@link DensityMaps.DensityMapBuilder#buildServer(ImageData)})
	 * @param channel channel of the density map to threshold
	 * @param levels the isoline levels, in the density map's own value units
	 * @param colorMap colormap used to colour each level's {@link PathClass}
	 * @param minDisplay value mapped to the first colormap entry
	 * @param maxDisplay value mapped to the last colormap entry
	 * @param options passed straight through to {@link DensityMaps#threshold}
	 * @return a map from level to the annotations created at that level, in the order levels were processed
	 * @throws IOException if thresholding or annotation creation fails
	 */
	public static Map<Double, List<PathObject>> createIsolines(
			PathObjectHierarchy hierarchy,
			ImageServer<BufferedImage> densityServer,
			int channel,
			double[] levels,
			ColorMap colorMap,
			double minDisplay,
			double maxDisplay,
			CreateObjectOptions... options) throws IOException {
		return createIsolines(hierarchy, densityServer, channel, levels, colorMap, minDisplay, maxDisplay,
				null, 0, options);
	}

	/**
	 * Create isoline annotations for a density map at the given levels, colouring each level's
	 * {@link PathClass} from {@code colorMap} using {@code minDisplay}/{@code maxDisplay} as the
	 * lookup range -- i.e. the same range that would have been used to colour the equivalent raster
	 * display, so isoline colours stay consistent with however the map would otherwise have looked.
	 * <p>
	 * Levels are processed from highest to lowest. This affects nothing about the resulting geometry --
	 * it only matters if you later choose to render these filled rather than as outlines, so that the
	 * smaller/innermost regions end up drawn last (on top).
	 * <p>
	 * If {@code maskObjects} is non-empty, those objects are selected in the hierarchy for the duration
	 * of this call, restricting isoline creation to their ROIs (e.g. a tissue annotation) and parenting
	 * the results under them -- see the class-level docs for the convention this relies on. Any prior
	 * selection is restored afterwards, whether or not this completes successfully.
	 *
	 * @param hierarchy hierarchy to add annotations to
	 * @param densityServer the density map (see {@link DensityMaps.DensityMapBuilder#buildServer(ImageData)})
	 * @param channel channel of the density map to threshold
	 * @param levels the isoline levels, in the density map's own value units
	 * @param colorMap colormap used to colour each level's {@link PathClass}
	 * @param minDisplay value mapped to the first colormap entry
	 * @param maxDisplay value mapped to the last colormap entry
	 * @param maskObjects optional objects (e.g. tissue annotations) to restrict isoline creation to;
	 *                     {@code null} or empty means the whole image
	 * @param simplifyTolerance if &gt; 0, each isoline's geometry is run through
	 *                          {@link TopologyPreservingSimplifier} at this tolerance (in pixels) immediately
	 *                          after it's created, to smooth out raw pixel-boundary jaggedness. {@code 0} (or
	 *                          negative) skips this -- geometry is left exactly as {@link DensityMaps#threshold}
	 *                          produced it. This only simplifies; it doesn't drop small islands/holes -- see
	 *                          {@link GeometryTools#refineAreas(org.locationtech.jts.geom.Geometry, double, double)}
	 *                          for that, as a separate manual step, if you find you need it.
	 * @param options passed straight through to {@link DensityMaps#threshold} -- check
	 *                {@link CreateObjectOptions} for what's available
	 * @return a map from level to the annotations created at that level, in the order levels were processed
	 * @throws IOException if thresholding or annotation creation fails
	 */
	public static Map<Double, List<PathObject>> createIsolines(
			PathObjectHierarchy hierarchy,
			ImageServer<BufferedImage> densityServer,
			int channel,
			double[] levels,
			ColorMap colorMap,
			double minDisplay,
			double maxDisplay,
			Collection<? extends PathObject> maskObjects,
			double simplifyTolerance,
			CreateObjectOptions... options) throws IOException {

		Objects.requireNonNull(hierarchy, "hierarchy must not be null");
		Objects.requireNonNull(densityServer, "densityServer must not be null");
		Objects.requireNonNull(levels, "levels must not be null");
		Objects.requireNonNull(colorMap, "colorMap must not be null");
		if (levels.length == 0)
			throw new IllegalArgumentException("levels must not be empty");

		var result = new LinkedHashMap<Double, List<PathObject>>();

		var sortedLevels = Arrays.stream(levels).boxed()
				.sorted(Comparator.reverseOrder())
				.toList();

		var selectionModel = hierarchy.getSelectionModel();
		var previousSelection = new LinkedHashSet<>(selectionModel.getSelectedObjects());
		boolean masked = maskObjects != null && !maskObjects.isEmpty();

		try {
			if (masked)
				selectionModel.setSelectedObjects(maskObjects, null);
			else
				selectionModel.clearSelection();

			for (double v : sortedLevels) {
				String name = "Isoline " + String.format(Locale.US, "%.4g", v);
				var pathClass = PathClass.fromString(name);

				// TODO verify against qupath.lib.color.ColorMaps before relying on this:
				//  - exact signature of ColorMap#getColor(double, double, double)
				//  - whether the returned int carries an alpha channel that needs masking
				//    (PathClass colours are typically plain RGB) before being passed to setColor
				int rgb = colorMap.getColor(v, minDisplay, maxDisplay);
				pathClass.setColor(rgb);

				logger.debug("Thresholding isoline at {} -> class '{}' (masked: {})", v, name, masked);
				DensityMaps.threshold(hierarchy, densityServer, Map.of(channel, v), name, options);

				var created = hierarchy.getAnnotationObjects().stream()
						.filter(p -> p.getPathClass() == pathClass)
						.toList();
				if (created.isEmpty())
					logger.warn("No isoline annotations created at level {} (channel {})", v, channel);
				else if (simplifyTolerance > 0)
					created.forEach(isoline -> simplify(isoline, simplifyTolerance));
				result.put(v, created);
			}
		} finally {
			selectionModel.setSelectedObjects(previousSelection, null);
		}

		hierarchy.fireHierarchyChangedEvent(Isolines.class);
		return result;
	}

	/**
	 * Replace {@code isoline}'s ROI with a {@link TopologyPreservingSimplifier}-simplified version of itself,
	 * at the given tolerance (in pixels). Called once per newly-created isoline when {@code simplifyTolerance > 0}.
	 * <p>
	 * {@code setROI} lives on {@link PathROIObject}, not on the {@link PathObject} interface itself -- the
	 * annotations {@link DensityMaps#threshold} creates are always {@code PathROIObject}s in practice, but
	 * this checks rather than assumes, and silently leaves anything else untouched.
	 */
	private static void simplify(PathObject isoline, double simplifyTolerance) {
		var roi = isoline.getROI();
		if (roi == null || !(isoline instanceof PathROIObject roiObject))
			return;
		var plane = roi.getImagePlane();
		var geometry = TopologyPreservingSimplifier.simplify(roi.getGeometry(), simplifyTolerance);
		roiObject.setROI(GeometryTools.geometryToROI(geometry, plane));
	}

	/**
	 * Convenience entry point: load a density map from its JSON, rebuild it for the given {@link ImageData},
	 * pull the colormap and display range straight out of the same JSON, generate {@code nLevels} evenly-spaced
	 * levels across that range, and create isoline annotations for all of them -- optionally restricted to
	 * {@code maskObjects} (e.g. a tissue annotation), per {@link #createIsolines(PathObjectHierarchy, ImageServer,
	 * int, double[], ColorMap, double, double, Collection, CreateObjectOptions...)}.
	 *
	 * @param imageData image to build the density map against
	 * @param densityMapJson path to the density map's saved JSON
	 * @param channel channel of the density map to threshold
	 * @param nLevels number of evenly-spaced levels to generate
	 * @param maskObjects optional objects to restrict isoline creation to; {@code null}/empty means the whole image
	 * @param simplifyTolerance if &gt; 0, simplification tolerance in pixels -- see
	 *                          {@link #createIsolines(PathObjectHierarchy, ImageServer, int, double[], ColorMap,
	 *                          double, double, Collection, double, CreateObjectOptions...)}
	 * @param options passed through to {@link #createIsolines}
	 * @return a map from level to the annotations created at that level
	 * @throws IOException if the JSON can't be read/parsed, the referenced colormap isn't registered,
	 *                      or thresholding fails
	 */
	public static Map<Double, List<PathObject>> createIsolinesFromJson(
			ImageData<BufferedImage> imageData,
			Path densityMapJson,
			int channel,
			int nLevels,
			Collection<? extends PathObject> maskObjects,
			double simplifyTolerance,
			CreateObjectOptions... options) throws IOException {

		Objects.requireNonNull(imageData, "imageData must not be null");
		Objects.requireNonNull(densityMapJson, "densityMapJson must not be null");

		var builder = DensityMaps.loadDensityMap(densityMapJson);
		var server = builder.buildServer(imageData);
		var colorInfo = readColorModelInfo(densityMapJson);

		var colorMap = ColorMaps.getColorMaps().get(colorInfo.getColorMapName());
		if (colorMap == null)
			throw new IOException("Colormap '" + colorInfo.getColorMapName()
					+ "' referenced in " + densityMapJson + " is not registered");

		double[] levels = computeLevels(colorInfo.getMinDisplay(), colorInfo.getMaxDisplay(), nLevels);

		return createIsolines(imageData.getHierarchy(), server, channel, levels, colorMap,
				colorInfo.getMinDisplay(), colorInfo.getMaxDisplay(), maskObjects, simplifyTolerance, options);
	}

	/**
	 * Convenience overload of {@link #createIsolinesFromJson(ImageData, Path, int, int, Collection, double,
	 * CreateObjectOptions...)} with no mask and no simplification -- isolines are created across the whole
	 * image, with geometry exactly as {@link DensityMaps#threshold} produced it.
	 */
	public static Map<Double, List<PathObject>> createIsolinesFromJson(
			ImageData<BufferedImage> imageData,
			Path densityMapJson,
			int channel,
			int nLevels,
			CreateObjectOptions... options) throws IOException {
		return createIsolinesFromJson(imageData, densityMapJson, channel, nLevels, Collections.emptyList(), 0, options);
	}

}
