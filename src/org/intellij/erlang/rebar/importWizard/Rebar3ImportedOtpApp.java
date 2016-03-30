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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.erlang.psi.*;
import org.intellij.erlang.rebar.util.ErlangTermFileUtil;
import org.intellij.erlang.rebar.util.RebarConfigUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

final class Rebar3ImportedOtpApp {
  public enum AppType {
    EXAMPLE_APP, RELEASE_ROOT, MY_APP, DEP_APP
  }

  private final AppType myAppType;
  private final String myName;
  private final VirtualFile myRoot;
  private final Set<String> myDeps = ContainerUtil.newHashSet();
  private final Set<VirtualFile> myIncludePaths = ContainerUtil.newHashSet();
  private final Set<String> myParseTransforms = ContainerUtil.newHashSet();
  private VirtualFile myIdeaModuleFile;
  private Module myModule;

  public Rebar3ImportedOtpApp(@NotNull AppType appType, @NotNull VirtualFile root, @NotNull final VirtualFile appConfig) {
    myName = StringUtil.trimEnd(StringUtil.trimEnd(appConfig.getName(), ".src"), ".app");
    myRoot = root;
    myAppType = appType;

    ApplicationManager.getApplication().runReadAction(new Runnable() {
      @Override
      public void run() {
        addDependenciesFromAppFile(appConfig);
        addInfoFromRebarConfig();
        addIncludePath("include");
      }
    });
  }

  public Rebar3ImportedOtpApp(@NotNull VirtualFile root){
    myName = root.getName() + "-release";
    myRoot = root;
    myAppType = AppType.RELEASE_ROOT;

    ApplicationManager.getApplication().runReadAction(new Runnable() {
      @Override
      public void run() {
        addInfoFromRebarConfig();
      }
    });
  }

  @NotNull
  public String getName() {
    return myName;
  }

  @NotNull
  public VirtualFile getRoot() {
    return myRoot;
  }

  @NotNull
  public Set<String> getDeps() {
    return myDeps;
  }

  @NotNull
  public AppType getAppType() {
    return myAppType;
  }

  public Set<VirtualFile> getIncludePaths() {
    return myIncludePaths;
  }

  public void setIdeaModuleFile(@Nullable VirtualFile ideaModuleFile) {
    myIdeaModuleFile = ideaModuleFile;
  }

  @Nullable
  public VirtualFile getIdeaModuleFile() {
    return myIdeaModuleFile;
  }

  public Module getModule() {
    return myModule;
  }

  public void setModule(Module module) {
    myModule = module;
  }

  public Set<String> getParseTransforms() {
    return myParseTransforms;
  }

  @Override
  public String toString() {
    return myName + " (" + myRoot + ')';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Rebar3ImportedOtpApp that = (Rebar3ImportedOtpApp) o;

    if (!myName.equals(that.myName)) return false;
    if (!myRoot.equals(that.myRoot)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myName.hashCode();
    result = 31 * result + myRoot.hashCode();
    return result;
  }

  private void addInfoFromRebarConfig() {
    VirtualFile rebarConfig = myRoot.findChild("rebar.config");
    ErlangFile rebarConfigPsi = rebarConfig != null ? ErlangTermFileUtil.createPsi(rebarConfig) : null;
    if (rebarConfigPsi == null) return;
    addDependenciesFromRebarConfig(rebarConfigPsi);
    addIncludePathsFromRebarConfig(rebarConfigPsi);
    addParseTransformsFromRebarConfig(rebarConfigPsi);
  }

  private void addDependenciesFromAppFile(@NotNull VirtualFile appFile) {
    ErlangFile appConfigPsi = ErlangTermFileUtil.createPsi(appFile);
    List<ErlangTupleExpression> applicationDescriptors = ErlangTermFileUtil.getConfigSections(appConfigPsi, "application");
    ErlangListExpression appAttributes = PsiTreeUtil.getChildOfType(ContainerUtil.getFirstItem(applicationDescriptors), ErlangListExpression.class);
    ErlangTermFileUtil.processConfigSection(appAttributes, "applications", new Consumer<ErlangExpression>() {
      @Override
      public void consume(ErlangExpression deps) {
        ErlangListExpression dependencyAppsList = deps instanceof ErlangListExpression ? (ErlangListExpression) deps : null;
        if (dependencyAppsList != null) {
          for (ErlangExpression depExpression : dependencyAppsList.getExpressionList()) {
            ErlangQAtom depApp = PsiTreeUtil.getChildOfType(depExpression, ErlangQAtom.class);
            ErlangAtom appNameAtom = depApp != null ? depApp.getAtom() : null;
            if (appNameAtom != null) {
              myDeps.add(appNameAtom.getName());
            }
          }
        }
      }
    });
  }

  private void addDependenciesFromRebarConfig(ErlangFile rebarConfig) {
    // myDeps.addAll(RebarConfigUtil.getDependencyAppNames(rebarConfig));
    // `deps` which in the `rebar.config` for Rebar3 support `ErlangQAtom` and `NamedTuple`.
    ErlangTermFileUtil.processConfigSection(rebarConfig, "deps", new Consumer<ErlangExpression>() {
      @Override
      public void consume(ErlangExpression deps) {
        ErlangListExpression dependencyAppsList = deps instanceof ErlangListExpression ? (ErlangListExpression) deps : null;
        if (dependencyAppsList != null) {
          for (ErlangExpression depExpression : dependencyAppsList.getExpressionList()) {
            ErlangQAtom depApp = PsiTreeUtil.getChildOfType(depExpression, ErlangQAtom.class);
            if(depApp != null){
              ErlangAtom appNameAtom = depApp.getAtom();
              if(appNameAtom != null){
                myDeps.add(appNameAtom.getName());
              }
            }else {
              String depAppName = ErlangTermFileUtil.getNameOfNamedTuple(depExpression);
              if(depAppName != null){
                myDeps.add(depAppName);
              }
            }
          }
        }
      }
    });
  }

  private void addIncludePathsFromRebarConfig(ErlangFile rebarConfig) {
    for (String includePath : RebarConfigUtil.getIncludePaths(rebarConfig)) {
      addIncludePath(includePath);
    }
  }

  private void addParseTransformsFromRebarConfig(ErlangFile rebarConfig) {
    myParseTransforms.addAll(RebarConfigUtil.getParseTransforms(rebarConfig));
  }

  private void addIncludePath(String relativeIncludePath) {
    VirtualFile path = VfsUtilCore.findRelativeFile(relativeIncludePath, myRoot);
    if (path != null) {
      myIncludePaths.add(path);
    }
  }
}
