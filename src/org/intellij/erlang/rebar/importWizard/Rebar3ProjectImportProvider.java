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

import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.ProjectJdkForModuleStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.projectImport.ProjectImportProvider;
import org.intellij.erlang.sdk.ErlangSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Rebar3ProjectImportProvider extends ProjectImportProvider {
  protected Rebar3ProjectImportProvider(@NotNull Rebar3ProjectImportBuilder builder) {
    super(builder);
  }

  @Override
  public ModuleWizardStep[] createSteps(@NotNull WizardContext context) {
    return new ModuleWizardStep[]{
      new Rebar3ProjectRootStep(context),
      new Rebar3SelectImportedOtpAppsStep(context),
      new ProjectJdkForModuleStep(context, ErlangSdkType.getInstance())
    };
  }

  @Override
  protected boolean canImportFromFile(VirtualFile file) {
    return "rebar.config".equals(file.getExtension());
  }

  @Override
  public String getPathToBeImported(VirtualFile file) {
    return file.getPath();
  }

  @Nullable
  @Override
  public String getFileSample() {
    return "<b>Rebar3</b> configuration file (rebar.config)";
  }
}
