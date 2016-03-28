/*
 * Copyright 2012-2016 Sergey Ignatov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.erlang.rebar.importWizard;

import com.intellij.compiler.CompilerWorkspaceConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkTypeId;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.ex.ProjectRootManagerEx;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.packaging.artifacts.ModifiableArtifactModel;
import com.intellij.projectImport.ProjectImportBuilder;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.erlang.configuration.ErlangCompilerSettings;
import org.intellij.erlang.facet.ErlangFacet;
import org.intellij.erlang.facet.ErlangFacetConfiguration;
import org.intellij.erlang.icons.ErlangIcons;
import org.intellij.erlang.module.ErlangModuleType;
import org.intellij.erlang.rebar.settings.RebarSettings;
import org.intellij.erlang.roots.ErlangIncludeDirectoryUtil;
import org.intellij.erlang.sdk.ErlangSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static com.intellij.openapi.vfs.VirtualFileVisitor.NO_FOLLOW_SYMLINKS;

public class Rebar3ProjectImportBuilder extends ProjectImportBuilder<Rebar3ImportedOtpApp> {
  private static final Logger LOG = Logger.getInstance(Rebar3ProjectImportBuilder.class);

  private static final String examples_DIR_NAME = "examples";
  private static final String _build_DIR_NAME = "_build";
  private static final String _checkouts_DIR_NAME = "_checkouts";
  private static final String apps_DIR_NAME = "apps";
  private static final String lib_DIR_NAME = "lib";

  private boolean myOpenProjectSettingsAfter = false;
  private boolean myImportExamples;
  private boolean myIsImportingProject;

  @Nullable private VirtualFile myProjectRoot = null;
  @NotNull private List<Rebar3ImportedOtpApp> myProjectApps = Collections.emptyList();
  @NotNull private List<Rebar3ImportedOtpApp> myFoundOtpApps = Collections.emptyList();
  @NotNull private List<Rebar3ImportedOtpApp> mySelectedOtpApps = Collections.emptyList();
  @NotNull private String myRebarPath = "";

  @NotNull
  @Override
  public String getName() {
    return "Rebar3";
  }

  @Override
  public Icon getIcon() {
    return ErlangIcons.REBAR3;
  }

  @Override
  public boolean isSuitableSdkType(@NotNull SdkTypeId sdkType) {
    return sdkType == ErlangSdkType.getInstance();
  }

  @Override
  public List<Rebar3ImportedOtpApp> getList() {
    return new ArrayList<Rebar3ImportedOtpApp>(myFoundOtpApps);
  }

  @Override
  public boolean isMarked(@Nullable Rebar3ImportedOtpApp importedOtpApp) {
    return importedOtpApp != null && mySelectedOtpApps.contains(importedOtpApp);
  }

  @Override
  public boolean validate(Project current, Project dest) {
    if(!findIdeaModuleFiles(mySelectedOtpApps)){
      return true;
    }

    int resultCode = Messages.showYesNoCancelDialog(
      ApplicationInfoEx.getInstanceEx().getFullApplicationName() + " module files found:\n\n" +
      StringUtil.join(mySelectedOtpApps, new Function<Rebar3ImportedOtpApp, String>() {
        @Override
        public String fun(Rebar3ImportedOtpApp importedOtpApp) {
          VirtualFile ideaModuleFile = importedOtpApp.getIdeaModuleFile();
          return ideaModuleFile != null ? "    " + ideaModuleFile.getPath() + "\n" : "";
        }
      }, "") + "\nWould you like to reuse them?", "Module Files Found", Messages.getQuestionIcon());

    if(resultCode == DialogWrapper.OK_EXIT_CODE){
      return true;
    }else if(resultCode == DialogWrapper.CANCEL_EXIT_CODE){
      try {
        deleteIdeaModuleFiles(mySelectedOtpApps);
        return true;
      }catch (Exception e){
        LOG.error(e);
        return false;
      }
    }else {
      return false;
    }
  }

  @Override
  public void setList(@Nullable List<Rebar3ImportedOtpApp> selectedOtpApps) throws ConfigurationException {
    if(selectedOtpApps != null){
      mySelectedOtpApps = selectedOtpApps;
    }
  }

  public void setRebarPath(@NotNull String rebarPath){
    myRebarPath = rebarPath;
  }

  public void setImportingProject(boolean isImportingProject) {
    myIsImportingProject = isImportingProject;
  }

  public void setImportExamples(boolean importExamples) {
    myImportExamples = importExamples;
  }

  @Override
  public void setOpenProjectSettingsAfter(boolean openProjectSettingsAfter) {
    myOpenProjectSettingsAfter = openProjectSettingsAfter;
  }

  @Override
  public boolean isOpenProjectSettingsAfter() {
    return myOpenProjectSettingsAfter;
  }

  @Override
  public void cleanup() {
    myOpenProjectSettingsAfter = false;
    myProjectRoot = null;
    myFoundOtpApps = Collections.emptyList();
    mySelectedOtpApps = Collections.emptyList();
  }

  @Nullable
  @Override
  public List<Module> commit(@NotNull Project project,
                             @Nullable ModifiableModuleModel modulemodel,
                             @NotNull ModulesProvider modulesProvider,
                             @Nullable ModifiableArtifactModel artifactModel) {
    Set<String> selectedAppNames = ContainerUtil.newHashSet();
    for(Rebar3ImportedOtpApp importedOtpApp: mySelectedOtpApps){
      selectedAppNames.add(importedOtpApp.getName());
    }
    Sdk projectSdk = fixProjectSdk(project);
    List<Module> createdModules = new ArrayList<Module>();
    final List<ModifiableRootModel> createdRootModels = new ArrayList<ModifiableRootModel>();
    final ModifiableModuleModel obtainedModuleModel =
      modulemodel != null ? modulemodel : ModuleManager.getInstance(project).getModifiableModel();

    for(Rebar3ImportedOtpApp importedOtpApp: mySelectedOtpApps){
      VirtualFile ideaModuleDir = importedOtpApp.getRoot();
      String ideaModuleFile = ideaModuleDir.getCanonicalPath() + File.separator + importedOtpApp.getName() + ".iml";
      Module module = obtainedModuleModel.newModule(ideaModuleFile, ErlangModuleType.getInstance().getId());
      createdModules.add(module);
      importedOtpApp.setModule(module);
      if(importedOtpApp.getIdeaModuleFile() == null){
        ModifiableRootModel rootModel = ModuleRootManager.getInstance(module).getModifiableModel();
        // Make it inherit SDK from the project
        rootModel.inheritSdk();
        // Initialize source and test paths
        ContentEntry content = rootModel.addContentEntry(importedOtpApp.getRoot());
        addSourceDirToContent(content, ideaModuleDir, "src", false);
        addSourceDirToContent(content, ideaModuleDir, "test", true);
        addIncludeDirectories(content, importedOtpApp);
        // Exclude standard folders
        excludeDirFromContent(content, ideaModuleDir, "doc");
        // Initialize output paths according to Rebar3 conventions.
        CompilerModuleExtension compilerModuleExt = rootModel.getModuleExtension(CompilerModuleExtension.class);

        if(importedOtpApp.getAppType() != Rebar3ImportedOtpApp.AppType.RELEASE_ROOT){
          compilerModuleExt.inheritCompilerOutputPath(false);

          switch (importedOtpApp.getAppType()){
            case DEP_APP:
            case EXAMPLE_APP:
              compilerModuleExt.setCompilerOutputPath(ideaModuleDir + File.separator + "ebin");
              compilerModuleExt.setCompilerOutputPathForTests(ideaModuleDir + File.separator + "test");
              break;
            default:  // MY_APP
              if(myProjectRoot != null){
                compilerModuleExt.setCompilerOutputPath(myProjectRoot.getPath() + File.separator + "_build" +
                                                        File.separator + "default" + File.separator + "lib" +
                                                        File.separator + importedOtpApp.getName() + File.separator + "ebin");
                compilerModuleExt.setCompilerOutputPathForTests(myProjectRoot.getPath() + File.separator + "_build" +
                                                                File.separator + "test" + File.separator + "lib" +
                                                                File.separator + importedOtpApp.getName() + File.separator + "test");
              }else{
                compilerModuleExt.setCompilerOutputPathForTests(ideaModuleDir + File.separator + "test");
              }
          }
        }
        createdRootModels.add(rootModel);
        // Set inter-module dependencies
        resolveModuleDeps(rootModel, importedOtpApp, projectSdk, selectedAppNames);
      }
    }

    // Commit project structure
    LOG.info("Commit project structure");
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        for(ModifiableRootModel rootModel: createdRootModels){
          rootModel.commit();
        }
        obtainedModuleModel.commit();
      }
    });

    addErlangFacets(mySelectedOtpApps);
    RebarSettings.getInstance(project).setRebarPath(myRebarPath);
    if(myIsImportingProject){
      ErlangCompilerSettings.getInstance(project).setUseRebarCompilerEnabled(true);
    }
    CompilerWorkspaceConfiguration.getInstance(project).CLEAR_OUTPUT_DIRECTORY = false;

    return createdModules;
  }

  public boolean setProjectRoot(@NotNull final VirtualFile projectRoot){
    if(projectRoot.equals(myProjectRoot)){
      return true;
    }

    boolean unitTestMode = ApplicationManager.getApplication().isUnitTestMode();

    myProjectRoot = projectRoot;
    if(!unitTestMode && projectRoot instanceof VirtualDirectoryImpl){
      ((VirtualDirectoryImpl) projectRoot).refreshAndFindChild("_build");
      ((VirtualDirectoryImpl) projectRoot).refreshAndFindChild("_checkouts");
      ((VirtualDirectoryImpl) projectRoot).refreshAndFindChild("apps");
      ((VirtualDirectoryImpl) projectRoot).refreshAndFindChild("lib");
    }

    ProgressManager.getInstance().run(new Task.Modal(getCurrentProject(), "Scanning Rebar3 projects", true){
      @Override
      public void run(@NotNull final ProgressIndicator indicator) {
        LinkedHashSet<Rebar3ImportedOtpApp> importedOtpApps = findOtpApps(myProjectRoot, indicator);
        myFoundOtpApps = ContainerUtil.newArrayList(importedOtpApps);
      }
    });

    Collections.sort(myFoundOtpApps, new Comparator<Rebar3ImportedOtpApp>() {
      @Override
      public int compare(@NotNull Rebar3ImportedOtpApp o1, @NotNull Rebar3ImportedOtpApp o2) {
        int nameCompareResult = String.CASE_INSENSITIVE_ORDER.compare(o1.getName(), o2.getName());
        if(nameCompareResult == 0){
          return String.CASE_INSENSITIVE_ORDER.compare(o1.getRoot().getPath(), o2.getRoot().getPath());
        }
        return nameCompareResult;
      }
    });

    mySelectedOtpApps = myFoundOtpApps;

    return !myFoundOtpApps.isEmpty();
  }

  @NotNull
  private LinkedHashSet<Rebar3ImportedOtpApp> findOtpApps(@NotNull final VirtualFile projectRoot, @NotNull final ProgressIndicator indicator){
    projectRoot.refresh(false, true);

    final LinkedHashSet<Rebar3ImportedOtpApp> foundOtpApps = new LinkedHashSet<Rebar3ImportedOtpApp>();

    // find projectRoot rebar.config, if don't exist, stop find others.
    VirtualFile projectRootRebarConfig = projectRoot.findChild("rebar.config");
    Rebar3ImportedOtpApp projectRootApp = createImportedOtpApp(Rebar3ImportedOtpApp.AppType.MY_APP, projectRoot);
    if(projectRootApp == null){
      return foundOtpApps;
    }
    foundOtpApps.add(projectRootApp);

    final Rebar3ImportedOtpApp.AppType[] appTypes = new Rebar3ImportedOtpApp.AppType[1];
    final VirtualFileVisitor visitor = new VirtualFileVisitor(NO_FOLLOW_SYMLINKS, VirtualFileVisitor.limit(2)) {
      @Override
      public boolean visitFile(@NotNull VirtualFile file) {
        indicator.checkCanceled();
        indicator.setText2(file.getPath());
        if(file.isDirectory()){
          if(isGitMetaDataDirectory(file.getName())) return false;
        }
        ContainerUtil.addAllNotNull(foundOtpApps, createImportedOtpApp(appTypes[0], file));
        return true;
      }
    };

    // examples
    appTypes[0] = Rebar3ImportedOtpApp.AppType.EXAMPLE_APP;
    if(myImportExamples){
      VirtualFile examplesDir = projectRoot.findChild(examples_DIR_NAME);
      if(examplesDir != null){
        VfsUtilCore.visitChildrenRecursively(examplesDir, visitor);
      }
    }

    // _checkouts
    appTypes[0] = Rebar3ImportedOtpApp.AppType.DEP_APP;
    VirtualFile _checkoutsDir = projectRoot.findChild(_checkouts_DIR_NAME);
    if(_checkoutsDir != null){
      VfsUtilCore.visitChildrenRecursively(_checkoutsDir, visitor);
    }

    // apps
    appTypes[0] = Rebar3ImportedOtpApp.AppType.MY_APP;
    VirtualFile appsDir = projectRoot.findChild(apps_DIR_NAME);
    if(appsDir != null){
      VfsUtilCore.visitChildrenRecursively(appsDir, visitor);
    }

    // lib
    appTypes[0] = Rebar3ImportedOtpApp.AppType.MY_APP;
    VirtualFile libDir = projectRoot.findChild(lib_DIR_NAME);
    if(libDir != null){
      VfsUtilCore.visitChildrenRecursively(libDir, visitor);
    }

    // _build
    appTypes[0] = Rebar3ImportedOtpApp.AppType.DEP_APP;
    VirtualFile _buildDir = projectRoot.findChild(_build_DIR_NAME);
    if(_buildDir != null){
      // _build/default/lib
      VirtualFile _buildDefaultLibDir = _buildDir.findChild("default");
      if(_buildDefaultLibDir != null){
        VfsUtilCore.visitChildrenRecursively(_buildDefaultLibDir, visitor);
      }
    }

    return foundOtpApps;
  }

  private boolean isExamplesDirectory(VirtualFile virtualFile){
    return "examples".equals(virtualFile.getName()) && !myImportExamples;
  }

  private static boolean isGitMetaDataDirectory(String directoryName){
    return directoryName.equals(".git");
  }

  @Nullable
  private static Rebar3ImportedOtpApp createImportedOtpApp(@NotNull Rebar3ImportedOtpApp.AppType appType, @NotNull VirtualFile appRoot){
    VirtualFile appResourceFile = findAppResourceFile(appRoot);
    if(appResourceFile != null){
      return new Rebar3ImportedOtpApp(appType, appRoot, appResourceFile);
    }

    if(appRoot.findChild(apps_DIR_NAME) != null ||
       (appRoot.getName().equals(_build_DIR_NAME) && appRoot.findChild(lib_DIR_NAME) != null)){

      return new Rebar3ImportedOtpApp(appRoot);
    }

    return null;
  }

  @Nullable
  private static VirtualFile findAppResourceFile(@NotNull VirtualFile applicationRoot){
    VirtualFile appResourceFile = null;
    VirtualFile sourceDir = applicationRoot.findChild("src");
    if(sourceDir != null){
      // no symlink
      if(sourceDir.is(VFileProperty.SYMLINK)) return null;

      appResourceFile = findFileByExtension(sourceDir, "app.src");
    }

    if (appResourceFile == null) {
      VirtualFile ebinDir = applicationRoot.findChild("ebin");
      if(ebinDir != null){
        appResourceFile = findFileByExtension(ebinDir, "app");
      }
    }

    return appResourceFile;
  }

  @Nullable
  private static VirtualFile findFileByExtension(@NotNull VirtualFile dir, @NotNull String extension){
    for(VirtualFile file: dir.getChildren()){
      String fileName = file.getName();
      if(!file.isDirectory() && fileName.endsWith(extension)){
        return file;
      }
    }
    return null;
  }

  private static boolean findIdeaModuleFiles(@NotNull List<Rebar3ImportedOtpApp> importedOtpApps){
    boolean ideaModuleFileExists = false;
    for(Rebar3ImportedOtpApp importedOtpApp: importedOtpApps){
      VirtualFile applicationRoot = importedOtpApp.getRoot();
      String ideaModuleName = importedOtpApp.getName();
      VirtualFile imlFile = applicationRoot.findChild(ideaModuleName + ".iml");
      if(imlFile != null){
        ideaModuleFileExists = true;
        importedOtpApp.setIdeaModuleFile(imlFile);
      }else{
        VirtualFile emlFile = applicationRoot.findFileByRelativePath(ideaModuleName + ".eml");
        if(emlFile != null){
          ideaModuleFileExists = true;
          importedOtpApp.setIdeaModuleFile(emlFile);
        }
      }
    }
    return ideaModuleFileExists;
  }

  private static void deleteIdeaModuleFiles(@NotNull final List<Rebar3ImportedOtpApp> importedOtpApps) throws IOException{
    final IOException[] ex = new IOException[1];
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        for(Rebar3ImportedOtpApp importedOtpApp: importedOtpApps){
          VirtualFile ideaModuleFile = importedOtpApp.getIdeaModuleFile();
          if(ideaModuleFile != null){
            try{
              ideaModuleFile.delete(this);
              importedOtpApp.setIdeaModuleFile(null);
            }catch (IOException e){
              ex[0] = e;
            }
          }

        }
      }
    });
    if(ex[0] != null){
      throw ex[0];
    }
  }

  @Nullable
  private static Sdk fixProjectSdk(@NotNull Project project){
    final ProjectRootManagerEx projectRootMgr = ProjectRootManagerEx.getInstanceEx(project);
    Sdk selectedSdk = projectRootMgr.getProjectSdk();
    if(selectedSdk == null || selectedSdk.getSdkType() != ErlangSdkType.getInstance()){
      final Sdk moreSuitableSdk = ProjectJdkTable.getInstance().findMostRecentSdkOfType(ErlangSdkType.getInstance());
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        @Override
        public void run() {
          projectRootMgr.setProjectSdk(moreSuitableSdk);
        }
      });
      return moreSuitableSdk;
    }
    return selectedSdk;
  }

  private static void addSourceDirToContent(@NotNull ContentEntry content,
                                            @NotNull VirtualFile root,
                                            @NotNull String sourceDir,
                                            boolean test) {
    VirtualFile sourceDirFile = root.findChild(sourceDir);
    if (sourceDirFile != null) {
      content.addSourceFolder(sourceDirFile, test);
    }
  }

  private static void addIncludeDirectories(@NotNull ContentEntry content, Rebar3ImportedOtpApp app) {
    for (VirtualFile includeDirectory : app.getIncludePaths()) {
      ErlangIncludeDirectoryUtil.markAsIncludeDirectory(content, includeDirectory);
    }
  }

  private static void excludeDirFromContent(ContentEntry content, VirtualFile root, String excludeDir) {
    VirtualFile excludeDirFile = root.findChild(excludeDir);
    if (excludeDirFile != null) {
      content.addExcludeFolder(excludeDirFile);
    }
  }

  @NotNull
  private static Set<String> resolveModuleDeps(@NotNull ModifiableRootModel rootModel,
                                               @NotNull Rebar3ImportedOtpApp importedOtpApp,
                                               @Nullable Sdk projectSdk,
                                               @NotNull Set<String> allImportedAppNames) {
    HashSet<String> unresolvedAppNames = ContainerUtil.newHashSet();
    for (String depAppName : importedOtpApp.getDeps()) {
      if (allImportedAppNames.contains(depAppName)) {
        rootModel.addInvalidModuleEntry(depAppName);
      }
      else if (projectSdk != null && isSdkOtpApp(depAppName, projectSdk)) {
        // SDK is already a dependency
      }
      else {
        rootModel.addInvalidModuleEntry(depAppName);
        unresolvedAppNames.add(depAppName);
      }
    }
    return unresolvedAppNames;
  }

  private static boolean isSdkOtpApp(@NotNull String otpAppName, @NotNull Sdk sdk) {
    Pattern appDirNamePattern = Pattern.compile(otpAppName + "-.*");
    for (VirtualFile srcSdkDir : sdk.getRootProvider().getFiles(OrderRootType.SOURCES)) {
      for (VirtualFile child : srcSdkDir.getChildren()) {
        if (child.isDirectory() && appDirNamePattern.matcher(child.getName()).find()) {
          return true;
        }
      }
    }
    return false;
  }

  private static void addErlangFacets(final List<Rebar3ImportedOtpApp> apps){
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        for(Rebar3ImportedOtpApp app: apps){
          Module module = app.getModule();
          if(module == null) continue;
          ErlangFacet facet = ErlangFacet.getFacet(module);
          if(facet == null){
            ErlangFacet.createFacet(module);
            facet = ErlangFacet.getFacet(module);
          }
          if(facet != null){
            ErlangFacetConfiguration configuration = facet.getConfiguration();
            configuration.addParseTransforms(app.getParseTransforms());
          }
        }
      }
    });
  }


}
