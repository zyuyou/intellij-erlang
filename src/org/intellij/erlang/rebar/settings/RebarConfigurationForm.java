/*
 * Copyright 2012-2014 Sergey Ignatov
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

package org.intellij.erlang.rebar.settings;

import com.google.common.collect.ImmutableList;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.labels.ActionLink;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.download.DownloadableFileDescription;
import com.intellij.util.download.DownloadableFileService;
import com.intellij.util.download.FileDownloader;
import org.intellij.erlang.icons.ErlangIcons;
import org.intellij.erlang.jps.model.JpsErlangSdkType;
import org.intellij.erlang.utils.ExtProcessUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.io.File;
import java.util.List;

public class RebarConfigurationForm {
  private JPanel myPanel;
  private TextFieldWithBrowseButton myRebarPathSelector;
  private JTextField myRebarVersionText;
  private JPanel myLinkContainer;

  private boolean myRebarPathValid;

  public RebarConfigurationForm() {
    myRebarPathSelector.addBrowseFolderListener("Select Rebar executable", "", null,
      FileChooserDescriptorFactory.createSingleLocalFileDescriptor());
    myRebarPathSelector.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent documentEvent) {
        myRebarPathValid = validateRebarPath();
      }
    });
    myRebarPathValid = false;
  }

  public void setPath(@NotNull String rebarPath) {
    if (!myRebarPathSelector.getText().equals(rebarPath)) {
      myRebarPathSelector.setText(rebarPath);
      myRebarPathValid = validateRebarPath();
    }
  }

  @NotNull
  public String getPath() {
    return myRebarPathSelector.getText();
  }

  public boolean isPathValid() {
    return myRebarPathValid;
  }

  @Nullable
  public JComponent createComponent() {
    return myPanel;
  }

  private boolean validateRebarPath() {
    String rebarPath = myRebarPathSelector.getText();
    if (!new File(rebarPath).exists()) return false;

    String escript = JpsErlangSdkType.getExecutableFileName(JpsErlangSdkType.SCRIPT_INTERPRETER);
    ExtProcessUtil.ExtProcessOutput output = ExtProcessUtil.execAndGetFirstLine(3000, escript, rebarPath, "--version");
    String version = output.getStdOut();
    if (version.startsWith("rebar")) {
      myRebarVersionText.setText(version);
      return true;
    }

    String stdErr = output.getStdErr();
    myRebarVersionText.setText("N/A" + (StringUtil.isNotEmpty(stdErr) ? ": Error: " + stdErr : ""));
    return false;
  }

  private void createUIComponents() {
    myLinkContainer = new JPanel(new BorderLayout());

    final ImmutableList<PopupAction> popupActions = ImmutableList.of(
      new PopupAction(ErlangIcons.REBAR, 1, "Rebar", "https://github.com/rebar/rebar/wiki/rebar") {
        @Override
        public void run() {
          doDownloadAction(getLowerCaseTitle(), getUrl());
        }
      },
      new PopupAction(ErlangIcons.REBAR, 2, "Rebar3", "https://s3.amazonaws.com/rebar3/rebar3"){
        @Override
        public void run() {
          doDownloadAction(getLowerCaseTitle(), getUrl());
        }
      }
    );

    ActionLink link = new ActionLink("Download the latest Rebar version", new AnAction() {
      @Override
      public void actionPerformed(AnActionEvent e) {
        final ListPopup popup = JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<PopupAction>("Choose Rebar version", popupActions){
          @NotNull
          @Override
          public String getTextFor(PopupAction value) {
            return " " + value.myTitle + "  [" + value.myUrl + "]";
          }

          @Override
          public Icon getIconFor(PopupAction value) {
            return value.myIcon;
          }

          @Override
          public boolean hasSubstep(PopupAction selectedValue) {
            return false;
          }

          @Override
          public boolean isMnemonicsNavigationEnabled() {
            return true;
          }

          @Override
          public PopupStep onChosen(final PopupAction selectedValue, boolean finalChoice) {
            return doFinalStep(new Runnable() {
              @Override
              public void run() {
                selectedValue.run();
              }
            });
          }
        });
        popup.showUnderneathOf(myLinkContainer);
      }
    });

    myLinkContainer.add(link, BorderLayout.NORTH);
  }

  private void doDownloadAction(String rebarName, String downloadUrl){
    DownloadableFileService service = DownloadableFileService.getInstance();
    DownloadableFileDescription rebar = service.createFileDescription(downloadUrl, rebarName);
    FileDownloader downloader = service.createDownloader(ContainerUtil.list(rebar), rebarName);
    List<Pair<VirtualFile, DownloadableFileDescription>> pairs = downloader.downloadWithProgress(null, null, myLinkContainer);
    if(pairs != null){
      for (Pair<VirtualFile, DownloadableFileDescription> pair: pairs){
        try {
          String path = pair.first.getCanonicalPath();
          if(path != null){
            FileUtilRt.setExecutableAttribute(path, true);
            myRebarPathSelector.setText(path);
            validateRebarPath();
          }
        }catch (Exception ignore){
        }
      }
    }
  }

  private abstract static class PopupAction implements Runnable {
    private Icon myIcon;
    private Object myIndex;
    private String myTitle;
    private String myUrl;

    protected PopupAction(Icon icon, Object index, String title, String url) {
      myIcon = icon;
      myIndex = index;
      myTitle = title;
      myUrl = url;
    }

    public String getLowerCaseTitle(){
      return StringUtil.toLowerCase(myTitle);
    }

    public String getUrl() {
      return myUrl;
    }
  }
}
