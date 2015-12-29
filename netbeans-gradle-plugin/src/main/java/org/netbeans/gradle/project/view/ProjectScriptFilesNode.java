package org.netbeans.gradle.project.view;

import java.awt.Image;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import javax.swing.Action;
import org.jtrim.utils.ExceptionHelper;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.NbIcons;
import org.netbeans.gradle.project.api.nodes.SingleNodeFactory;
import org.netbeans.gradle.project.model.NbGradleModel;
import org.netbeans.gradle.project.properties.SettingsFiles;
import org.netbeans.gradle.project.query.GradleFilesClassPathProvider;
import org.netbeans.gradle.project.util.ListenerRegistrations;
import org.netbeans.gradle.project.util.NbFileUtils;
import org.netbeans.gradle.project.util.RefreshableChildren;
import org.netbeans.gradle.project.util.StringUtils;
import org.netbeans.spi.project.ui.PathFinder;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.ChildFactory;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

public final class ProjectScriptFilesNode extends AbstractNode {
    private final String caption;
    private final NbGradleProject project;

    public ProjectScriptFilesNode(String caption, NbGradleProject project) {
        this(caption, project, new ProjectScriptFilesChildFactory(project));
    }

    private ProjectScriptFilesNode(
            String caption,
            NbGradleProject project,
            ProjectScriptFilesChildFactory childFactory) {
        this(caption, project, childFactory, Children.create(childFactory, true));
    }

    private ProjectScriptFilesNode(
            String caption,
            NbGradleProject project,
            ProjectScriptFilesChildFactory childFactory,
            Children children) {
        super(children, createLookup(childFactory, children));

        ExceptionHelper.checkNotNullArgument(caption, "caption");
        ExceptionHelper.checkNotNullArgument(project, "project");

        this.caption = caption;
        this.project = project;

        setName(caption);
    }

    private static Lookup createLookup(ProjectScriptFilesChildFactory childFactory, Children children) {
        return Lookups.fixed(
                ProjectScriptFileFinder.INSTANCE,
                NodeUtils.defaultNodeRefresher(children, childFactory));
    }

    public static SingleNodeFactory getFactory(String caption, NbGradleProject project) {
        return new FactoryImpl(caption, project);
    }

    private Action openProjectFileAction(String name) {
        Path file = project.getProjectDirectoryAsPath().resolve(name);
        return new OpenAlwaysFileAction(file);
    }

    private static void addOpenFileAction(Path file, List<Action> actions) {
        if (file != null) {
            actions.add(new OpenAlwaysFileAction(file));
        }
    }

    @Override
    public Action[] getActions(boolean context) {
        List<Action> actions = new ArrayList<>(5);

        NbGradleModel currentModel = project.currentModel().getValue();
        if (currentModel.isRootProject()) {
            addOpenFileAction(currentModel.getSettingsFile(), actions);
        }
        addOpenFileAction(currentModel.getBuildFile().toPath(), actions);
        actions.add(openProjectFileAction(SettingsFiles.GRADLE_PROPERTIES_NAME));
        actions.add(null);
        actions.add(NodeUtils.getRefreshNodeAction(this));

        return actions.toArray(new Action[actions.size()]);
    }

    @Override
    public Image getIcon(int type) {
        return NbIcons.getFolderIcon();
    }

    @Override
    public Image getOpenedIcon(int type) {
        return NbIcons.getOpenFolderIcon();
    }

    @Override
    public String getDisplayName() {
        return caption;
    }

    private enum ProjectScriptFileFinder implements PathFinder {
        INSTANCE;

        private Node findNodeByFile(Node root, FileObject target) {
            boolean canBeFound =
                    SettingsFiles.GRADLE_PROPERTIES_NAME.equalsIgnoreCase(target.getNameExt())
                    || SettingsFiles.DEFAULT_GRADLE_EXTENSION_WITHOUT_DOT.equalsIgnoreCase(target.getExt());
            if (!canBeFound) {
                return null;
            }

            return NodeUtils.findFileChildNode(root.getChildren(), target);
        }

        @Override
        public Node findPath(Node root, Object target) {
            FileObject targetFile = NodeUtils.tryGetFileSearchTarget(target);
            return targetFile != null
                    ? findNodeByFile(root, targetFile)
                    : null;
        }
    }

    private static class ProjectScriptFilesChildFactory
    extends
            ChildFactory.Detachable<SingleNodeFactory>
    implements
            RefreshableChildren {
        private final NbGradleProject project;
        private final ListenerRegistrations listenerRefs;
        private volatile boolean createdOnce;

        public ProjectScriptFilesChildFactory(NbGradleProject project) {
            ExceptionHelper.checkNotNullArgument(project, "project");
            this.project = project;
            this.listenerRefs = new ListenerRegistrations();
            this.createdOnce = false;
        }

        @Override
        public void refreshChildren() {
            if (createdOnce) {
                refresh(false);
            }
        }

        @Override
        protected void addNotify() {
            Runnable refreshChildrenTask = new Runnable() {
                @Override
                public void run() {
                    refreshChildren();
                }
            };

            listenerRefs.add(project.currentModel().addChangeListener(refreshChildrenTask));
            listenerRefs.add(NbFileUtils.addDirectoryContentListener(project.getProjectDirectory(), refreshChildrenTask));
        }

        @Override
        protected void removeNotify() {
            listenerRefs.unregisterAll();
        }

        private void addFileObject(
                FileObject file,
                List<SingleNodeFactory> toPopulate) {
            SingleNodeFactory nodeFactory = NodeUtils.tryGetFileNode(file);
            if (nodeFactory != null) {
                toPopulate.add(nodeFactory);
            }
        }

        private void addGradleFile(
                FileObject file,
                List<SingleNodeFactory> toPopulate) {
            addGradleFile(file, file.getNameExt(), toPopulate);
        }

        private void addGradleFile(
                FileObject file,
                final String name,
                List<SingleNodeFactory> toPopulate) {

            SingleNodeFactory nodeFactory = NodeUtils.tryGetFileNode(file, name, NbIcons.getGradleIcon());
            if (nodeFactory != null) {
                toPopulate.add(nodeFactory);
            }
        }

        private static File getLocalGradleProperties(NbGradleModel model) {
            return new File(model.getProjectDir(), SettingsFiles.GRADLE_PROPERTIES_NAME);
        }

        private static FileObject tryGetLocalGradlePropertiesObj(NbGradleModel model) {
            File result = getLocalGradleProperties(model);
            return FileUtil.toFileObject(result);
        }

        private void readKeys(List<SingleNodeFactory> toPopulate) {
            NbGradleModel model = project.currentModel().getValue();

            FileObject settingsGradle = model.tryGetSettingsFileObj();
            if (settingsGradle != null && model.isRootProject()) {
                addGradleFile(settingsGradle, toPopulate);
            }

            FileObject buildGradle = model.tryGetBuildFileObj();
            if (buildGradle != null) {
                addGradleFile(buildGradle, toPopulate);
            }

            FileObject propertiesFile = tryGetLocalGradlePropertiesObj(model);
            if (propertiesFile != null) {
                addFileObject(propertiesFile, toPopulate);
            }

            List<FileObject> gradleFiles = new LinkedList<>();
            for (FileObject file: project.getProjectDirectory().getChildren()) {
                if (file.equals(buildGradle) || file.equals(settingsGradle)) {
                    continue;
                }

                if (GradleFilesClassPathProvider.isGradleFile(file)) {
                    gradleFiles.add(file);
                }
            }

            Collections.sort(gradleFiles, new Comparator<FileObject>() {
                @Override
                public int compare(FileObject o1, FileObject o2) {
                    return StringUtils.STR_CMP.compare(o1.getNameExt(), o2.getNameExt());
                }
            });

            for (FileObject file: gradleFiles) {
                addGradleFile(file, toPopulate);
            }
        }

        @Override
        protected boolean createKeys(List<SingleNodeFactory> toPopulate) {
            createdOnce = true;
            readKeys(toPopulate);
            return true;
        }

        @Override
        protected Node createNodeForKey(SingleNodeFactory key) {
            return key.createNode();
        }
    }

    private static class FactoryImpl implements SingleNodeFactory {
        private final String caption;
        private final NbGradleProject project;
        private final File projectDir;

        public FactoryImpl(String caption, NbGradleProject project) {
            ExceptionHelper.checkNotNullArgument(caption, "caption");
            ExceptionHelper.checkNotNullArgument(project, "project");

            this.caption = caption;
            this.project = project;
            this.projectDir = project.getProjectDirectoryAsFile();
        }

        @Override
        public Node createNode() {
            return new ProjectScriptFilesNode(caption, project);
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 61 * hash + Objects.hashCode(this.caption);
            hash = 61 * hash + Objects.hashCode(this.projectDir);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;

            final FactoryImpl other = (FactoryImpl)obj;
            return Objects.equals(this.caption, other.caption)
                    && Objects.equals(this.projectDir, other.projectDir);
        }
    }
}