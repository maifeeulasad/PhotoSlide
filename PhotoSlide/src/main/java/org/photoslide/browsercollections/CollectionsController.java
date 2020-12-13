/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.photoslide.browsercollections;

import org.photoslide.MainViewController;
import org.photoslide.ThreadFactoryPS;
import org.photoslide.Utility;
import org.photoslide.browserlighttable.LighttableController;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Accordion;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.javafx.StackedFontIcon;

/**
 *
 * @author selfemp
 */
public class CollectionsController implements Initializable {

    @FXML
    private MenuItem pasteMenu;
    @FXML
    private MenuItem deleteMenu;
    private Image iconImage;
    private SearchIndex searchIndexProcess;

    private enum ClipboardMode {
        CUT,
        COPY
    }
    private ExecutorService executor;
    private ExecutorService executorParallel;
    private ScheduledExecutorService executorParallelTimers;

    private Utility util;
    private static final String NODE_NAME = "PhotoSlide";
    private Path selectedPath;
    private LinkedHashMap<String, String> collectionStorage;
    private int activeAccordionPane;
    private Path clipboardPath;
    private ClipboardMode clipboardMode;

    private MainViewController mainController;
    @FXML
    private Button minusButton;
    @FXML
    private Button plusButton;
    @FXML
    private MenuButton menuButton;
    @FXML
    private Accordion accordionPane;

    private LighttableController lighttablePaneController;
    private Preferences pref;
    private static TreeItem placeholder;
    private ProgressIndicator waitPrg;
    @FXML
    private Button refreshButton;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        executor = Executors.newSingleThreadExecutor(new ThreadFactoryPS("collectionsController"));
        executorParallel = Executors.newCachedThreadPool(new ThreadFactoryPS("collectionsControllerParallel"));
        executorParallelTimers = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryPS("collectionsControllerParallelScheduled"));
        util = new Utility();
        pref = Preferences.userRoot().node(NODE_NAME);
        collectionStorage = new LinkedHashMap<>();
        placeholder = new TreeItem("Please wait...");
        waitPrg = new ProgressIndicator();
        waitPrg.setPrefSize(15, 15);
        placeholder.setGraphic(waitPrg);
        iconImage = new Image(getClass().getResourceAsStream("/org/photoslide/img/Installericon.png"));
        accordionPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                searchIndexProcess = new SearchIndex(mainController.getMetadataPaneController());
                loadURLs();
            }
        });
    }

    private void loadURLs() {
        Task<Boolean> indexTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                collectionStorage.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach((t) -> {
                    if (this.isCancelled() == false) {
                        searchIndexProcess.createSearchIndex(t.getValue());
                        searchIndexProcess.checkSearchIndex(t.getValue());
                    }
                });
                return true;
            }
        };
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                collectionStorage.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach((t) -> {
                    if (this.isCancelled() == false) {
                        loadDirectoryTree(t.getValue());
                    }
                });
                return true;
            }
        };
        task.setOnSucceeded((WorkerStateEvent t) -> {
            Platform.runLater(() -> {
                if (accordionPane.getPanes().size() > 0) {
                    accordionPane.setExpandedPane(accordionPane.getPanes().get(activeAccordionPane));
                }
            });
        });
        executor.submit(task);
        executorParallelTimers.schedule(indexTask, 5, TimeUnit.SECONDS);
    }

    public void saveSettings() {
        pref.putInt("activeAccordionPane", accordionPane.getPanes().indexOf(accordionPane.getExpandedPane()));
    }

    public void restoreSettings() {
        try {
            activeAccordionPane = pref.getInt("activeAccordionPane", 0);
            String[] keys = pref.keys();
            for (String key : keys) {
                if (key.contains("URL")) {
                    collectionStorage.put(key, pref.get(key, null));
                }
            }
        } catch (BackingStoreException ex) {
            Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void injectMainController(MainViewController mainController) {
        this.mainController = mainController;
    }

    public void injectLighttableController(LighttableController mainController) {
        this.lighttablePaneController = mainController;
    }

    private void createRootTree(Path root_file, TreeItem parent) throws IOException {
        Platform.runLater(() -> {
            mainController.getProgressPane().setVisible(true);
            mainController.getStatusLabelLeft().setText("Scanning...");
        });
        try ( DirectoryStream<Path> newDirectoryStream = Files.newDirectoryStream(root_file, (entry) -> {
            boolean res = true;
            if (entry.getFileName().toString().startsWith(".")) {
                res = false;
            }
            if (entry.getFileName().toString().startsWith("@")) {
                res = false;
            }
            return res;
        })) {
            Stream<Path> sortedStream = StreamSupport.stream(newDirectoryStream.spliterator(), false).sorted();
            final AtomicInteger i = new AtomicInteger(0);
            final long qty = Files.list(root_file).count();
            sortedStream.forEach((t) -> {
                //try {
                Platform.runLater(() -> {
                    double prgValue = ((double) (i.addAndGet(1)) / qty * 100);
                    //System.out.println(String.format("%1$,.2f", prgValue));
                    //mainController.getProgressbar().setProgress(prgValue);
                    mainController.getProgressbarLabel().setText(t.toString() + " " + String.format("%1$,.0f", prgValue) + "%");
                });
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, ex);
                }

                Task<Boolean> taskTree = new Task<>() {
                    @Override
                    protected Boolean call() throws Exception {
                        createTree(t, parent);
                        return null;
                    }
                };
                taskTree.setOnSucceeded((k) -> {
                    mainController.getProgressPane().setVisible(false);
                    mainController.getStatusLabelLeft().setVisible(false);
                });
                taskTree.setOnFailed((t2) -> {
                    Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, t2.getSource().getException());
                    util.showError(this.accordionPane, "Cannot create directory tree", t2.getSource().getException());
                });
                executor.submit(taskTree);
                //} catch (IOException ex) {
                //    Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, ex);
                //}                
            });

        }
    }

    private void createTree(Path root_file, TreeItem parent) throws IOException {
        if (Files.isDirectory(root_file)) {
            TreeItem<PathItem> node = new TreeItem(new PathItem(root_file));
            parent.getChildren().add(node);
            try ( DirectoryStream<Path> newDirectoryStream = Files.newDirectoryStream(root_file, (entry) -> {
                boolean res = true;
                if (entry.getFileName().toString().startsWith(".")) {
                    res = false;
                }
                if (entry.getFileName().toString().startsWith("@")) {
                    res = false;
                }
                return res;
            })) {
                Stream<Path> sortedStream = StreamSupport.stream(newDirectoryStream.spliterator(), false).sorted();
                sortedStream.forEach((t) -> {
                    Platform.runLater(() -> {
                        if (node.getChildren().isEmpty()) {
                            node.getChildren().add(placeholder);
                        }
                    });

                    EventHandler eventH = new EventHandler() {
                        @Override
                        public void handle(Event event) {

                            Task<Boolean> taskTree = new Task<>() {
                                @Override
                                protected Boolean call() throws Exception {
                                    try {
                                        createTree(t, node); // Continue the recursive as usual                                        
                                    } catch (IOException ex) {
                                        Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, ex);
                                    }
                                    return null;
                                }
                            };
                            taskTree.setOnSucceeded((WorkerStateEvent t) -> {
                                if (node.getChildren().size() > 0) {
                                    node.getChildren().remove(placeholder); // Remove placeholder
                                }
                                node.removeEventHandler(TreeItem.branchExpandedEvent(), this); // Remove event                                                                
                            });
                            taskTree.setOnFailed((WorkerStateEvent t) -> {
                                mainController.getStatusLabelLeft().setText(t.getSource().getMessage());
                                util.hideNodeAfterTime(mainController.getStatusLabelLeft(), 10);
                            });
                            executor.submit(taskTree);

                        }
                    };
                    node.addEventHandler(TreeItem.branchExpandedEvent(), eventH);
                });
            }
        } else {
            //parent.getChildren().add(new TreeItem(root_file.getFileName()));
        }
    }

    public void Shutdown() {
        executor.shutdownNow();
        executorParallel.shutdownNow();
        if (searchIndexProcess != null) {
            searchIndexProcess.shutdown();
        }
        executorParallelTimers.shutdownNow();
    }

    @FXML
    private void plusButtonAction(ActionEvent event) {
        addExistingPath();
    }

    public void addExistingPath() {
        Stage stage = (Stage) accordionPane.getScene().getWindow();
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setInitialDirectory(new File(System.getProperty("user.dir")));
        File selectedDirectory = directoryChooser.showDialog(stage);
        if (selectedDirectory != null) {
            loadDirectoryTree(selectedDirectory.getAbsolutePath());
            pref.put("URL" + getPrefKeyForSaving(), selectedDirectory.getAbsolutePath());
        }
    }

    private String getPrefKeyForSaving() {
        try {
            String[] keys = pref.keys();
            ArrayList<Integer> numbers = new ArrayList<>();
            for (String key : keys) {
                if (key.contains("URL")) {
                    numbers.add(Integer.parseInt(key.substring(3)));
                }
            }
            Integer i = Collections.max(numbers);
            return "" + (i + 1);
        } catch (BackingStoreException ex) {
            Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    private void loadDirectoryTree(String selectedRootPath) {
        String path = selectedRootPath;
        TreeItem<PathItem> root = new TreeItem<>(new PathItem(Paths.get(path)));
        TreeView<PathItem> dirTreeView = new TreeView<>();
        dirTreeView.setShowRoot(false);
        dirTreeView.setDisable(true);
        TitledPane firstTitlePane = new TitledPane(path, dirTreeView);
        firstTitlePane.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        firstTitlePane.setAnimated(true);
        firstTitlePane.setTextAlignment(TextAlignment.LEFT);
        Platform.runLater(() -> {
            accordionPane.getPanes().add(firstTitlePane);
        });
        Task<TreeItem<PathItem>> task = new Task<TreeItem<PathItem>>() {
            @Override
            protected TreeItem<PathItem> call() throws Exception {
                Platform.runLater(() -> {
                    dirTreeView.setRoot(root);
                    mainController.getProgressPane().setVisible(true);
                    mainController.getStatusLabelLeft().setVisible(true);
                    mainController.getStatusLabelLeft().setText("Scanning...");
                    mainController.getProgressbar().setProgress(-1);
                });
                Thread.sleep(100);
                createRootTree(Paths.get(path), root);
                return root;
            }
        };
        task.setOnSucceeded((WorkerStateEvent t) -> {
            root.setExpanded(true);
            dirTreeView.setDisable(false);
            if (root.getChildren().isEmpty()) {
                dirTreeView.setShowRoot(true);
            }
            dirTreeView.getSelectionModel().selectedItemProperty().addListener((ObservableValue<? extends TreeItem<PathItem>> ov, TreeItem<PathItem> t1, TreeItem<PathItem> t2) -> {
                TreeItem<PathItem> selectedItem = (TreeItem<PathItem>) t2;
                if (selectedItem != null) {
                    selectedPath = selectedItem.getValue().getFilePath();
                    lighttablePaneController.setSelectedPath(selectedItem.getValue().getFilePath());
                }
            });
            mainController.getStatusLabelLeft().setVisible(false);
            mainController.getProgressPane().setVisible(false);
        });
        task.setOnFailed((WorkerStateEvent t) -> {
            mainController.getProgressPane().setVisible(false);
            mainController.getStatusLabelLeft().setText(t.getSource().getMessage());
            util.hideNodeAfterTime(mainController.getStatusLabelLeft(), 10);
            Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, t.getSource().getException());
            util.showError(this.accordionPane, "Cannot create directory tree", t.getSource().getException());
        });
        executorParallel.submit(task);
    }

    public Path getSelectedPath() {
        return selectedPath;
    }

    @FXML
    private void refreshMenuAction(ActionEvent event) {
        refreshTree();
    }

    private void refreshTree() {
        try {
            TreeItem<PathItem> parent;
            TreeView<PathItem> treeView = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
            ObservableList<TreeItem<PathItem>> selectedItems = treeView.getSelectionModel().getSelectedItems();
            String selectedItemName = selectedItems.get(0).getValue().toString();
            if (selectedItems.get(0).getParent() == null) {
                parent = selectedItems.get(0);
                parent.getChildren().clear();
                //createTree(parent.getValue().getFilePath(), parent);
                createRootTree(parent.getValue().getFilePath(), parent);
                SortedList<TreeItem<PathItem>> sorted = parent.getChildren().sorted();
                parent.getChildren().setAll(sorted);
                //Optional<TreeItem<PathItem>> findFirst = sorted.stream().filter(obj -> obj.getValue().toString().equalsIgnoreCase(selectedItemName)).findFirst();
                //treeView.getSelectionModel().select(findFirst.get());
            } else {
                parent = selectedItems.get(0).getParent();
                Path filePath = selectedItems.get(0).getValue().getFilePath();
                parent.getChildren().remove(selectedItems.get(0));
                createTree(filePath, parent);
                SortedList<TreeItem<PathItem>> sorted = parent.getChildren().sorted();
                parent.getChildren().setAll(sorted);
                Optional<TreeItem<PathItem>> findFirst = sorted.stream().filter(obj -> obj.getValue().toString().equalsIgnoreCase(selectedItemName)).findFirst();
                treeView.getSelectionModel().select(findFirst.get());
            }
        } catch (IOException ex) {
            Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, ex);
            util.showError(this.accordionPane, "Cannot create directory tree", ex);
        }
    }

    @FXML
    private void minusButtonAction(ActionEvent event) {
        TreeView<PathItem> content = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
        PathItem value = content.getRoot().getValue();

        collectionStorage.entrySet().stream().filter(c -> c.getValue().equalsIgnoreCase(value.getFilePath().toString())).forEach((t) -> {
            pref.remove(t.getKey());
        });
        lighttablePaneController.resetLightTableView();
        accordionPane.getPanes().remove(accordionPane.getExpandedPane());
    }

    private boolean checkIfElementInTreeSelected(String message) {
        TreeView<PathItem> treeView = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
        ObservableList<TreeItem<PathItem>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            StackedFontIcon st = new StackedFontIcon();
            FontIcon f1 = new FontIcon("ti-layout-width-full");
            f1.setIconSize(50);
            FontIcon f2 = new FontIcon("ti-close");
            f2.setIconSize(30);
            st.getChildren().add(f1);
            st.getChildren().add(f2);
            alert.setGraphic(st);
            alert.setContentText(message);
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/org/photoslide/fxml/Dialogs.css").toExternalForm());
            alert.setResizable(false);
            Stage stage = (Stage) alert.getDialogPane().getScene().getWindow();
            stage.getIcons().add(iconImage);
            Utility.centerChildWindowOnStage((Stage) alert.getDialogPane().getScene().getWindow(), (Stage) accordionPane.getScene().getWindow());
            alert.showAndWait();
            return true;
        }
        return false;
    }

    @FXML
    private void createCollectionAction(ActionEvent event) {
        if (checkIfElementInTreeSelected("Please select an element in tree first to create a child collection!")) {
            return;
        }
        TreeView<PathItem> treeView = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
        ObservableList<TreeItem<PathItem>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        TreeItem<PathItem> parent = selectedItems.get(0);
        TextInputDialog alert = new TextInputDialog();
        alert.setTitle("Create event");
        alert.setHeaderText("Create event (directory)");
        StackedFontIcon stackIcon = new StackedFontIcon();
        FontIcon fileIcon = new FontIcon("ti-file");
        fileIcon.setIconSize(50);
        FontIcon plusIcon = new FontIcon("ti-plus");
        plusIcon.setIconSize(20);
        stackIcon.getChildren().add(fileIcon);
        stackIcon.getChildren().add(plusIcon);
        alert.setGraphic(stackIcon);
        alert.setContentText("Please enter the name:");
        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.getStylesheets().add(
                getClass().getResource("/org/photoslide/fxml/Dialogs.css").toExternalForm());
        alert.setResizable(false);
        Stage stage = (Stage) alert.getDialogPane().getScene().getWindow();
        stage.getIcons().add(iconImage);
        Utility.centerChildWindowOnStage((Stage) alert.getDialogPane().getScene().getWindow(), (Stage) accordionPane.getScene().getWindow());
        Optional<String> result = alert.showAndWait();
        result.ifPresent((t) -> {
            Path filePath = selectedItems.get(0).getValue().getFilePath();
            String newPath = filePath.toString() + File.separator + t;
            //Paths.get(newPath).toFile().mkdir();                 
            TreeItem<PathItem> newChild = new TreeItem<>(new PathItem(Paths.get(newPath)));
            parent.getChildren().add(newChild);
            System.out.println("newPath " + newPath);
        });
    }

    @FXML
    private void cutCollectionAction(ActionEvent event) {
        if (checkIfElementInTreeSelected("Please select an element in the tree to be cut!")) {
            return;
        }
        TreeView<PathItem> treeView = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
        ObservableList<TreeItem<PathItem>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        TreeItem<PathItem> item = selectedItems.get(0);
        clipboardPath = item.getValue().getFilePath();
        clipboardMode = ClipboardMode.CUT;
        pasteMenu.setDisable(false);
        treeView.getSelectionModel().clearSelection();
        mainController.getStatusLabelLeft().setVisible(true);
        mainController.getStatusLabelLeft().setText("Cut collection " + clipboardPath.getFileName().toString());
        util.hideNodeAfterTime(mainController.getStatusLabelLeft(), 3);
    }

    @FXML
    private void copyCollectionAction(ActionEvent event) {
        if (checkIfElementInTreeSelected("Please select an element in the tree to be copied!")) {
            return;
        }
        TreeView<PathItem> treeView = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
        ObservableList<TreeItem<PathItem>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        TreeItem<PathItem> item = selectedItems.get(0);
        clipboardPath = item.getValue().getFilePath();
        clipboardMode = ClipboardMode.COPY;
        pasteMenu.setDisable(false);
        treeView.getSelectionModel().clearSelection();
        mainController.getStatusLabelLeft().setVisible(true);
        mainController.getStatusLabelLeft().setText("Copy collection " + clipboardPath.getFileName().toString());
        util.hideNodeAfterTime(mainController.getStatusLabelLeft(), 3);
    }

    @FXML
    private void deleteCollectionAction(ActionEvent event) {
        if (checkIfElementInTreeSelected("Please select an element in the tree to be deleted!")) {
            return;
        }
        TreeView<PathItem> treeView = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
        ObservableList<TreeItem<PathItem>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        TreeItem<PathItem> item = selectedItems.get(0);
        clipboardPath = item.getValue().getFilePath();

        Alert alert = new Alert(AlertType.CONFIRMATION, "Delete '" + clipboardPath + "' ?", ButtonType.CANCEL, ButtonType.OK);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/org/photoslide/fxml/Dialogs.css").toExternalForm());
        Stage stage = (Stage) alert.getDialogPane().getScene().getWindow();
        stage.getIcons().add(iconImage);
        Utility.centerChildWindowOnStage((Stage) alert.getDialogPane().getScene().getWindow(), (Stage) treeView.getScene().getWindow());
        Optional<ButtonType> resultDiag = alert.showAndWait();
        if (resultDiag.get() == ButtonType.OK) {
            deleteMenu.setDisable(true);
            mainController.getProgressPane().setVisible(true);
            mainController.getProgressbar().setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            mainController.getStatusLabelLeft().setText("Delete collection");
            mainController.getProgressbarLabel().setText("...");
            Task<Boolean> taskDelete = new Task<>() {
                @Override
                protected Boolean call() throws IOException {
                    Files.walk(clipboardPath)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach((t) -> {
                                Platform.runLater(() -> {
                                    mainController.getProgressbarLabel().setText("Delete " + t.getName());
                                });
                                t.delete();
                            });
                    return true;
                }
            };
            taskDelete.setOnSucceeded((t) -> {
                refreshTree();
                treeView.getSelectionModel().clearSelection();
                mainController.getProgressbar().progressProperty().unbind();
                mainController.getProgressbarLabel().textProperty().unbind();
                mainController.getProgressPane().setVisible(false);
                mainController.getStatusLabelLeft().setVisible(false);
                deleteMenu.setDisable(false);
            });
            taskDelete.setOnFailed((t) -> {
                treeView.getSelectionModel().clearSelection();
                util.showError(this.accordionPane, "Cannot delete collection", t.getSource().getException());
                mainController.getProgressbar().progressProperty().unbind();
                mainController.getProgressbarLabel().textProperty().unbind();
                mainController.getProgressPane().setVisible(false);
                mainController.getStatusLabelLeft().setVisible(false);
                deleteMenu.setDisable(true);
                Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, "Cannot cut/copy collection!", t.getSource().getException());
            });
            executor.submit(taskDelete);
        }
    }

    @FXML
    private void pasteCollectionAction(ActionEvent event) {
        Path sourceFilePath = clipboardPath;

        TreeView<PathItem> treeView = (TreeView<PathItem>) accordionPane.getExpandedPane().getContent();
        ObservableList<TreeItem<PathItem>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        TreeItem<PathItem> item = selectedItems.get(0);
        Path targetPath = item.getValue().getFilePath();
        mainController.getProgressPane().setVisible(true);
        mainController.getProgressbar().setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        mainController.getStatusLabelLeft().setText("Paste collection");
        if (clipboardMode == ClipboardMode.CUT) {
            mainController.getProgressbarLabel().setText("Moving files...");
        } else {
            mainController.getProgressbarLabel().setText("Copying files...");
        }
        mainController.getStatusLabelLeft().setVisible(true);
        Task<Boolean> taskPaste = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                try {
                    if (clipboardMode == ClipboardMode.CUT) {
                        //cut
                        final Path targetFinalPath = Paths.get(targetPath.toString(), sourceFilePath.getFileName().toString());
                        targetPath.toFile().mkdir();
                        copyMoveFolder(sourceFilePath, targetFinalPath, clipboardMode, StandardCopyOption.REPLACE_EXISTING);
                        Files.delete(sourceFilePath);
                    } else {
                        //copy
                        final Path targetFinalPath = Paths.get(targetPath.toString(), sourceFilePath.getFileName().toString());
                        targetPath.toFile().mkdir();
                        copyMoveFolder(sourceFilePath, targetFinalPath, clipboardMode, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ex) {
                    Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, null, ex);
                }
                return true;
            }
        };
        taskPaste.setOnSucceeded((t) -> {
            refreshTree();
            treeView.getSelectionModel().clearSelection();
            mainController.getProgressbar().progressProperty().unbind();
            mainController.getProgressbarLabel().textProperty().unbind();
            mainController.getProgressPane().setVisible(false);
            mainController.getStatusLabelLeft().setVisible(false);
            pasteMenu.setDisable(true);
        });
        taskPaste.setOnFailed((t) -> {
            treeView.getSelectionModel().clearSelection();
            util.showError(this.accordionPane, "Cannot cut/copy collection", t.getSource().getException());
            mainController.getProgressbar().progressProperty().unbind();
            mainController.getProgressbarLabel().textProperty().unbind();
            mainController.getProgressPane().setVisible(false);
            mainController.getStatusLabelLeft().setVisible(false);
            pasteMenu.setDisable(true);
            Logger.getLogger(CollectionsController.class.getName()).log(Level.SEVERE, "Cannot cut/copy collection!", t.getSource().getException());
        });
        executor.submit(taskPaste);
    }

    public void copyMoveFolder(Path source, Path target, ClipboardMode mode, CopyOption... options)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (mode == ClipboardMode.COPY) {
                    Platform.runLater(() -> {
                        mainController.getProgressbarLabel().setText("Copy " + file.toFile().getName());
                    });
                    Files.copy(file, target.resolve(source.relativize(file)), options);
                } else {
                    Platform.runLater(() -> {
                        mainController.getProgressbarLabel().setText("Move " + file.toFile().getName());
                    });
                    Files.move(file, target.resolve(source.relativize(file)), options);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public LinkedHashMap<String, String> getCollectionStorage() {
        return collectionStorage;
    }

}