package qupath.ext.isolines.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.isolines.Isolines;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.analysis.heatmaps.DensityMaps;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javafx.util.StringConverter;

/**
 * Controller for the isolines-generation dialog contained in interface.fxml.
 * <p>
 * Two independent actions are offered:
 * <ul>
 *   <li>{@link #generateIsolines()} runs {@link Isolines#createIsolinesFromJson} directly against the
 *       currently open image, using whatever is currently set in the dialog.</li>
 *   <li>{@link #generateCode()} fills in {@code scripts/IsolinesTemplate.groovy} with the same settings
 *       and opens the result as a new, unsaved script -- mirroring the pattern used by the StarDist
 *       extension's bundled scripts.</li>
 * </ul>
 * Neither action depends on the other having been run first.
 */
public class InterfaceController extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(InterfaceController.class);

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.isolines.ui.strings");

    /**
     * Prefix used by {@link Isolines} for every level's PathClass name (see {@code Isolines.createIsolines}).
     * Used here (a) to identify annotations to remove when 'Clear existing isolines' is checked, and
     * (b) in the generated script's commented-out post-processing block.
     */
	private static final String ISOLINE_CLASS_NAME = "Isoline";

    private static final String TEMPLATE_RESOURCE = "scripts/IsolinesTemplate.groovy";

    private static final String CLEAR_BLOCK =
            "// Clear all previously-generated isoline annotations before creating new ones.\n" +
            "def existingIsolines = hierarchy.getAnnotationObjects().findAll {\n" +
            "    it.getPathClass() == getPathClass(\"" + ISOLINE_CLASS_NAME + "\")\n" +
            "}\n" +
            "if (!existingIsolines.isEmpty())\n" +
            "    hierarchy.removeObjects(existingIsolines, true)\n";

    private static final String MASK_BLOCK =
            "// Restrict isoline creation to annotations classified as \"{{MASK_CLASS}}\" (e.g. a tissue region).\n" +
            "def maskClass = getPathClass(\"{{MASK_CLASS}}\")\n" +
            "def maskObjects = getAnnotationObjects().findAll { it.getPathClass() == maskClass }\n";

    private static final String MASK_BLOCK_NONE =
            "// No mask -- isolines are created across the whole image.\n" +
            "def maskObjects = []\n";

    @FXML
    private TextField heatmapPathField;
    @FXML
    private Button btnBrowseHeatmap;
    @FXML
    private HBox channelBox;
    @FXML
    private ComboBox<Integer> comboChannel;
    @FXML
    private Spinner<Integer> levelsSpinner;
    @FXML
    private CheckBox cbClearExisting;
    @FXML
    private CheckBox cbMaskByClass;
    @FXML
    private ComboBox<PathClass> comboMaskClass;
    @FXML
    private CheckBox cbSimplify;
    @FXML
    private Spinner<Double> simplifyToleranceSpinner;
    @FXML
    private Button btnGenerateIsolines;
    @FXML
    private Button btnGenerateCode;

    /**
     * Display names for the currently loaded density map's channels, indexed to match
     * {@link #comboChannel}'s items. Populated by {@link #updateChannelCombo(Path)}.
     */
    private final List<String> channelNames = new ArrayList<>();

    /**
     * The heatmap JSON path currently chosen via {@link #browseHeatmap()}.
     * Deliberately just the literal file the user picked (rather than, say, a project resource
     * name resolved back to a path) -- that keeps this in lock-step with what actually gets
     * embedded in the generated script, with nothing to get out of sync.
     */
    private Path heatmapPath;

    private static final PathClass UNCLASSIFIED = PathClass.fromString("None");

    private static final String MASK_BLOCK_UNCLASSIFIED =
            "// Restrict isoline creation to unclassified annotations.\n" +
            "def maskObjects = getAnnotationObjects().findAll { it.getPathClass() == null }\n";

    /**
     * Create a new instance of the interface controller.
     * @return a new instance of the interface controller
     * @throws IOException If reading the extension FXML files fails.
     */
    public static InterfaceController createInstance() throws IOException {
        return new InterfaceController();
    }

    private InterfaceController() throws IOException {
        var url = InterfaceController.class.getResource("interface.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();

        // Configure combo box display for null / unclassified items
        comboMaskClass.setConverter(new StringConverter<PathClass>() {
            @Override
            public String toString(PathClass pathClass) {
                return pathClass == null ? "None" : pathClass.getName();
            }

            @Override
            public PathClass fromString(String string) {
                return null;
            }
        });        

        comboMaskClass.setDisable(!cbMaskByClass.isSelected());
        cbMaskByClass.selectedProperty().addListener((obs, oldValue, newValue) -> {
            comboMaskClass.setDisable(!newValue);
            if (newValue)
                refreshMaskClasses();
        });

        // Show the channel index alongside its name (e.g. "0: Positive %"), from channelNames
        comboChannel.setConverter(new StringConverter<Integer>() {
            @Override
            public String toString(Integer index) {
                if (index == null)
                    return "";
                if (index >= 0 && index < channelNames.size())
                    return index + ": " + channelNames.get(index);
                return index.toString();
            }

            @Override
            public Integer fromString(String string) {
                return null;
            }
        });
        updateChannelCombo(null);

        simplifyToleranceSpinner.setDisable(!cbSimplify.isSelected());
        cbSimplify.selectedProperty().addListener((obs, oldValue, newValue) ->
                simplifyToleranceSpinner.setDisable(!newValue));
    }

    @FXML
    private void browseHeatmap() {
        var qupath = QuPathGUI.getInstance();
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            return;
        }

        File baseDir = Projects.getBaseDirectory(project); // project's base directory as a File
        File densityMapsDir = new File(baseDir, "classifiers/density_maps");

        if (densityMapsDir.exists() && densityMapsDir.isDirectory()) {
            baseDir = densityMapsDir;
        }

        File heatmapFile = FileChoosers.promptForFile(
                null,
                resources.getString("heatmap.browse.title"),
                baseDir,
                FileChoosers.createExtensionFilter(resources.getString("heatmap.filter.description"),
                ".*json")
            );
        if (heatmapFile == null) {
            return;
        }

        setHeatmapPath(heatmapFile.toPath());
    }

    private void setHeatmapPath(Path path) {
        this.heatmapPath = path;
        heatmapPathField.setText(path == null ? "" : path.toString());
        updateChannelCombo(path);
    }

    private Path getHeatmapPath() {
        return heatmapPath;
    }

    /**
     * Refresh {@link #comboChannel} from the density map at {@code path}, against the currently open image.
     * If the map turns out to have only one channel (the common case -- see the class docs), there's nothing
     * to choose, so the whole row is hidden and channel 0 is used silently; the combo (with real channel
     * names, not just indices) is only shown when there's an actual decision to make.
     */
    private void updateChannelCombo(Path path) {
        var qupath = QuPathGUI.getInstance();
        ImageData<BufferedImage> imageData = qupath == null ? null : qupath.imageDataProperty().getValue();

        channelNames.clear();
        int nChannels = 1;
        if (path != null && imageData != null) {
            try {
                var server = DensityMaps.loadDensityMap(path).buildServer(imageData);
                nChannels = server.nChannels();
                for (int i = 0; i < nChannels; i++)
                    channelNames.add(server.getChannel(i).getName());
            } catch (IOException e) {
                logger.warn("Could not inspect density map channels for {}", path, e);
            }
        }

        boolean multiChannel = nChannels > 1;
        channelBox.setVisible(multiChannel);
        channelBox.setManaged(multiChannel);

        comboChannel.getItems().setAll(IntStream.range(0, nChannels).boxed().toList());
        comboChannel.getSelectionModel().selectFirst();
    }

    private int getChannel() {
        Integer channel = comboChannel.getSelectionModel().getSelectedItem();
        return channel == null ? 0 : channel;
    }

    private double getSimplifyTolerance() {
        return cbSimplify.isSelected() ? simplifyToleranceSpinner.getValue() : 0;
    }

    /**
     * Refresh the mask-class combo box from the classifications currently present among the
     * annotations of the open image. This is deliberately scanned from the live hierarchy rather
     * than from e.g. {@code QuPathGUI#getAvailablePathClasses()} -- that method looked like the more
     * obvious choice, but isn't exercised anywhere in the files you gave me, so this sticks to
     * {@code PathObject#getPathClass()} / {@code PathObjectHierarchy#getAnnotationObjects()}, both of
     * which are already used inside Isolines.java itself.
     */
    private void refreshMaskClasses() {
        var qupath = QuPathGUI.getInstance();
        ImageData<BufferedImage> imageData = qupath == null ? null : qupath.imageDataProperty().getValue();
        comboMaskClass.getItems().clear();
        if (imageData == null)
            return;

        var hierarchy = imageData.getHierarchy();

        // Check if there are any non-point annotations without a class
        boolean hasUnclassified = hierarchy.getAnnotationObjects().stream()
                .filter(p -> !PathObjectTools.hasPointROI(p))
                .anyMatch(p -> p.getPathClass() == null);

        // Extract all distinct non-null classes from non-point annotations
        List<PathClass> classes = hierarchy.getAnnotationObjects().stream()
                .filter(p -> !PathObjectTools.hasPointROI(p))
                .map(PathObject::getPathClass)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(PathClass::toString))
                .collect(Collectors.toList());

        // Add the placeholder at the beginning to represent unclassified
        if (hasUnclassified) {
            classes.add(0, UNCLASSIFIED);
        }

        comboMaskClass.getItems().setAll(classes);
    }

    private List<PathObject> getMaskObjects(PathObjectHierarchy hierarchy) {
        if (!cbMaskByClass.isSelected()) return Collections.emptyList();
            
        PathClass selected = comboMaskClass.getSelectionModel().getSelectedItem();
        if (selected == null) return Collections.emptyList(); // Nothing was selected yet

        boolean isUnclassified = selected.equals(UNCLASSIFIED);
        return hierarchy.getAnnotationObjects().stream()
                .filter(p -> !PathObjectTools.hasPointROI(p))
                .filter(p -> isUnclassified ? p.getPathClass() == null : Objects.equals(p.getPathClass(), selected))
                .toList();
    }

    private void clearExistingIsolines(PathObjectHierarchy hierarchy) {
        //PathClass isolineClass = PathClass.fromString(ISOLINE_CLASS_NAME);
        
        var existing = hierarchy.getAnnotationObjects().stream()
                // Cleaner method commented out but the filter needs to consider isolines generated with v0.1.0
                //.filter(p -> isolineClass.equals(p.getPathClass()))
                .filter(p -> p.getPathClass() != null && p.getPathClass().toString().startsWith(ISOLINE_CLASS_NAME))
                .toList();
                
        if (!existing.isEmpty()) {
            hierarchy.removeObjects(existing, true);
        }
    }

    @FXML
    private void generateIsolines() {
        var qupath = QuPathGUI.getInstance();
        ImageData<BufferedImage> imageData = qupath == null ? null : qupath.imageDataProperty().getValue();
        if (imageData == null) {
            Dialogs.showErrorMessage(resources.getString("generate.title"), resources.getString("generate.noimage"));
            return;
        }
        Path path = getHeatmapPath();
        if (path == null) {
            Dialogs.showErrorMessage(resources.getString("generate.title"), resources.getString("generate.noheatmap"));
            return;
        }

        int channel = getChannel();
        int nLevels = levelsSpinner.getValue();
        double simplifyTolerance = getSimplifyTolerance();
        var hierarchy = imageData.getHierarchy();

        // TODO: this runs synchronously on the FX thread for simplicity. Each level does a full raster
        //  pass over the density server (see Isolines' class docs), so for many levels or large images
        //  this is worth moving to a background thread rather than blocking the UI, the way
        //  LoadResourceCommand's live preview or a PixelClassifierTools task would.
        try {
            if (cbClearExisting.isSelected())
                clearExistingIsolines(hierarchy);

            List<PathObject> maskObjects = getMaskObjects(hierarchy);

            Isolines.createIsolinesFromJson(imageData, path, channel, nLevels, maskObjects, simplifyTolerance);

            Dialogs.showInfoNotification(resources.getString("generate.title"), resources.getString("generate.success"));
        } catch (IOException e) {
            Dialogs.showErrorMessage(resources.getString("generate.title"), e.getLocalizedMessage());
            logger.error("Error generating isolines", e);
        }
    }

    @FXML
    private void generateCode() {
        Path path = getHeatmapPath();
        if (path == null) {
            Dialogs.showErrorMessage(resources.getString("generatecode.title"), resources.getString("generate.noheatmap"));
            return;
        }
        var qupath = QuPathGUI.getInstance();
        var editor = qupath == null ? null : qupath.getScriptEditor();
        if (editor == null) {
            Dialogs.showErrorMessage(resources.getString("generatecode.title"), resources.getString("error.no-script-editor"));
            logger.error("No script editor is available!");
            return;
        }
        try {
            String script = buildScript(
                    path,
                    getChannel(),
                    levelsSpinner.getValue(),
                    cbClearExisting.isSelected(),
                    cbMaskByClass.isSelected(),
                    comboMaskClass.getSelectionModel().getSelectedItem(),
                    getSimplifyTolerance());
            editor.showScript(resources.getString("script.name"), script);
        } catch (IOException e) {
            Dialogs.showErrorMessage(resources.getString("generatecode.title"), e.getLocalizedMessage());
            logger.error("Error generating isolines script", e);
        }
    }

    /**
     * Fill in {@code scripts/IsolinesTemplate.groovy} (loaded via the classloader, following the same
     * pattern StarDistExtension uses for its bundled scripts) with the settings currently shown in
     * the dialog.
     */
    private String buildScript(Path heatmapJsonPath, int channel, int nLevels, boolean clearExisting,
            boolean maskByClass, PathClass maskClass, double simplifyTolerance) throws IOException {

        String template;
        try (var stream = InterfaceController.class.getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (stream == null)
                throw new IOException("Could not find script template resource: " + TEMPLATE_RESOURCE);
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Escape backslashes so the path survives as a Groovy string literal (matters mostly on Windows,
        // c.f. the hardcoded "X:\\QuPathProjects\\..." path in the original iso_lines.groovy).
        String escapedPath = heatmapJsonPath.toString().replace("\\", "\\\\");

        String clearBlock = clearExisting ? CLEAR_BLOCK : "";

        String maskBlock;
        if (maskByClass && maskClass != null) {
            maskBlock = maskClass.equals(UNCLASSIFIED)
                    ? MASK_BLOCK_UNCLASSIFIED
                    : MASK_BLOCK.replace("{{MASK_CLASS}}", maskClass.toString());
        } else {
            maskBlock = MASK_BLOCK_NONE;
        }

        return template
                .replace("{{HEATMAP_PATH}}", escapedPath)
                .replace("{{CHANNEL}}", Integer.toString(channel))
                .replace("{{N_LEVELS}}", Integer.toString(nLevels))
                .replace("{{CLEAR_BLOCK}}", clearBlock)
                .replace("{{MASK_BLOCK}}", maskBlock)
                .replace("{{SIMPLIFY_TOLERANCE}}", Double.toString(simplifyTolerance));
    }

}
