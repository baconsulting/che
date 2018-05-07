/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.ide.ext.java.client.refactoring.rename.wizard;

import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;
import static org.eclipse.che.ide.ext.java.shared.dto.refactoring.CreateRenameRefactoring.RenameType.COMPILATION_UNIT;
import static org.eclipse.che.ide.ext.java.shared.dto.refactoring.CreateRenameRefactoring.RenameType.PACKAGE;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.editor.EditorAgent;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.editor.text.TextPosition;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.filewatcher.ClientServerEventService;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.java.client.JavaLocalizationConstant;
import org.eclipse.che.ide.ext.java.client.refactoring.RefactorInfo;
import org.eclipse.che.ide.ext.java.client.refactoring.RefactoringUpdater;
import org.eclipse.che.ide.ext.java.client.refactoring.move.RefactoredItemType;
import org.eclipse.che.ide.ext.java.client.refactoring.preview.PreviewPresenter;
import org.eclipse.che.ide.ext.java.client.refactoring.rename.wizard.RenameView.ActionDelegate;
import org.eclipse.che.ide.ext.java.client.refactoring.rename.wizard.similarnames.SimilarNamesConfigurationPresenter;
import org.eclipse.che.ide.ext.java.client.service.JavaLanguageExtensionServiceClient;
import org.eclipse.che.ide.ext.java.shared.dto.refactoring.ChangeInfo;
import org.eclipse.che.ide.ui.dialogs.DialogFactory;
import org.eclipse.che.jdt.ls.extension.api.RenameType;
import org.eclipse.che.jdt.ls.extension.api.dto.NameValidationStatus;
import org.eclipse.che.jdt.ls.extension.api.dto.RenameSelectionParams;
import org.eclipse.che.jdt.ls.extension.api.dto.RenameSettings;
import org.eclipse.che.jdt.ls.extension.api.dto.RenameWizardType;
import org.eclipse.che.plugin.languageserver.ide.util.DtoBuildHelper;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;

/**
 * The class that manages Rename panel widget.
 *
 * @author Valeriy Svydenko
 */
@Singleton
public class RenamePresenter implements ActionDelegate {
  private final RenameView view;
  private JavaLanguageExtensionServiceClient extensionServiceClient;
  private final SimilarNamesConfigurationPresenter similarNamesConfigurationPresenter;
  private final JavaLocalizationConstant locale;
  private final DtoBuildHelper dtoBuildHelper;
  private final RefactoringUpdater refactoringUpdater;
  private final EditorAgent editorAgent;
  private final NotificationManager notificationManager;
  private final AppContext appContext;
  private final PreviewPresenter previewPresenter;
  private final DtoFactory dtoFactory;
  private final DialogFactory dialogFactory;
  private final ClientServerEventService clientServerEventService;

  @Inject
  public RenamePresenter(
      RenameView view,
      JavaLanguageExtensionServiceClient extensionServiceClient,
      SimilarNamesConfigurationPresenter similarNamesConfigurationPresenter,
      JavaLocalizationConstant locale,
      DtoBuildHelper dtoBuildHelper,
      EditorAgent editorAgent,
      RefactoringUpdater refactoringUpdater,
      AppContext appContext,
      NotificationManager notificationManager,
      PreviewPresenter previewPresenter,
      ClientServerEventService clientServerEventService,
      DtoFactory dtoFactory,
      DialogFactory dialogFactory) {
    this.view = view;
    this.extensionServiceClient = extensionServiceClient;
    this.similarNamesConfigurationPresenter = similarNamesConfigurationPresenter;
    this.locale = locale;
    this.dtoBuildHelper = dtoBuildHelper;
    this.refactoringUpdater = refactoringUpdater;
    this.editorAgent = editorAgent;
    this.notificationManager = notificationManager;
    this.clientServerEventService = clientServerEventService;
    this.view.setDelegate(this);
    this.appContext = appContext;
    this.previewPresenter = previewPresenter;
    this.dtoFactory = dtoFactory;
    this.dialogFactory = dialogFactory;
  }

  /**
   * Show Rename window with the special information.
   *
   * @param refactorInfo information about the rename operation
   */
  public void show(RefactorInfo refactorInfo) {
    TextEditor editor = (TextEditor) editorAgent.getActiveEditor();

    RenameSelectionParams params = dtoFactory.createDto(RenameSelectionParams.class);

    if (RefactoredItemType.JAVA_ELEMENT.equals(refactorInfo.getRefactoredItemType())) {
      TextPosition cursorPosition = editor.getCursorPosition();
      org.eclipse.lsp4j.Position position = dtoFactory.createDto(org.eclipse.lsp4j.Position.class);
      position.setCharacter(cursorPosition.getCharacter());
      position.setLine(cursorPosition.getLine());
      params.setPosition(position);
      String location =
          editorAgent.getActiveEditor().getEditorInput().getFile().getLocation().toString();
      params.setResourceUri(location);
      params.setRenameType(RenameType.JAVA_ELEMENT);
    } else {
      // get selected resource
      Resource resource = refactorInfo.getResources()[0];
      params.setResourceUri(resource.getLocation().toString());
      if (RefactoredItemType.COMPILATION_UNIT.equals(refactorInfo.getRefactoredItemType())) {
        params.setRenameType(RenameType.COMPILATION_UNIT);
      } else {
        params.setRenameType(RenameType.PACKAGE);
      }
    }

    extensionServiceClient
        .getRenameType(params)
        .then(this::showWizard)
        .catchError(
            error -> {
              notificationManager.notify(
                  locale.failedToRename(), error.getMessage(), FAIL, FLOAT_MODE);
            });
  }

  private void showWizard(RenameWizardType renameWizard) {
    prepareWizard(renameWizard.getElementName());

    switch (renameWizard.getRenameType()) {
      case COMPILATION_UNIT:
        view.setTitleCaption(locale.renameCompilationUnitTitle());
        view.setVisiblePatternsPanel(true);
        view.setVisibleFullQualifiedNamePanel(true);
        view.setVisibleSimilarlyVariablesPanel(true);
        break;
      case PACKAGE:
        view.setTitleCaption(locale.renamePackageTitle());
        view.setVisiblePatternsPanel(true);
        view.setVisibleFullQualifiedNamePanel(true);
        view.setVisibleRenameSubpackagesPanel(true);
        break;
      case TYPE:
        view.setTitleCaption(locale.renameTypeTitle());
        view.setVisiblePatternsPanel(true);
        view.setVisibleFullQualifiedNamePanel(true);
        view.setVisibleSimilarlyVariablesPanel(true);
        break;
      case FIELD:
        view.setTitleCaption(locale.renameFieldTitle());
        view.setVisiblePatternsPanel(true);
        break;
      case ENUM_CONSTANT:
        view.setTitleCaption(locale.renameEnumTitle());
        view.setVisiblePatternsPanel(true);
        break;
      case TYPE_PARAMETER:
        view.setTitleCaption(locale.renameTypeVariableTitle());
        break;
      case METHOD:
        view.setTitleCaption(locale.renameMethodTitle());
        view.setVisibleKeepOriginalPanel(true);
        break;
      case LOCAL_VARIABLE:
        view.setTitleCaption(locale.renameLocalVariableTitle());
        break;
      default:
    }

    view.showDialog();
  }

  private void prepareWizard(String oldName) {
    view.clearErrorLabel();
    view.setOldName(oldName);
    view.setVisiblePatternsPanel(false);
    view.setVisibleFullQualifiedNamePanel(false);
    view.setVisibleKeepOriginalPanel(false);
    view.setVisibleRenameSubpackagesPanel(false);
    view.setVisibleSimilarlyVariablesPanel(false);
    view.setEnableAcceptButton(false);
    view.setEnablePreviewButton(false);
  }

  /** {@inheritDoc} */
  @Override
  public void onPreviewButtonClicked() {
    showPreview();
  }

  /** {@inheritDoc} */
  @Override
  public void onAcceptButtonClicked() {
    applyChanges();
  }

  /** {@inheritDoc} */
  @Override
  public void onCancelButtonClicked() {
    setEditorFocus();
  }

  private void setEditorFocus() {
    EditorPartPresenter activeEditor = editorAgent.getActiveEditor();
    if (activeEditor instanceof TextEditor) {
      ((TextEditor) activeEditor).setFocus();
    }
  }

  /** {@inheritDoc} */
  @Override
  public void validateName() {
    TextEditor editor = (TextEditor) editorAgent.getActiveEditor();

    TextPosition cursorPosition = editor.getCursorPosition();
    org.eclipse.lsp4j.Position position = dtoFactory.createDto(org.eclipse.lsp4j.Position.class);
    position.setCharacter(cursorPosition.getCharacter());
    position.setLine(cursorPosition.getLine());

    RenameSelectionParams params = dtoFactory.createDto(RenameSelectionParams.class);
    String location =
        editorAgent.getActiveEditor().getEditorInput().getFile().getLocation().toString();
    params.setResourceUri(location);
    params.setPosition(position);

    params.setNewName(view.getNewName());

    extensionServiceClient
        .validateRenamedName(params)
        .then(
            (Operation<NameValidationStatus>)
                status -> {
                  switch (status.getRefactoringSeverity()) {
                    case OK:
                      view.setEnableAcceptButton(true);
                      view.setEnablePreviewButton(true);
                      view.clearErrorLabel();
                      break;
                    case INFO:
                      view.setEnableAcceptButton(true);
                      view.setEnablePreviewButton(true);
                      view.showStatusMessage(status);
                      break;
                    default:
                      view.setEnableAcceptButton(false);
                      view.setEnablePreviewButton(false);
                      view.showErrorMessage(status);
                      break;
                  }
                })
        .catchError(
            error -> {
              notificationManager.notify(
                  locale.failedToRename(), error.getMessage(), FAIL, FLOAT_MODE);
            });
  }

  private void showPreview() {
    //    RefactoringSession session = dtoFactory.createDto(RefactoringSession.class);
    //    session.setSessionId(renameRefactoringSession.getSessionId());
    //
    //    prepareRenameChanges(session)
    //        .then(
    //            new Operation<ChangeCreationResult>() {
    //              @Override
    //              public void apply(ChangeCreationResult arg) throws OperationException {
    //                if (arg.isCanShowPreviewPage() || arg.getStatus().getSeverity() <= 3) {
    //                  previewPresenter.show(renameRefactoringSession.getSessionId(),
    // refactorInfo);
    //                  previewPresenter.setTitle(locale.renameItemTitle());
    //                  view.close();
    //                } else {
    //                  view.showErrorMessage(arg.getStatus());
    //                }
    //              }
    //            })
    //        .catchError(
    //            new Operation<PromiseError>() {
    //              @Override
    //              public void apply(PromiseError arg) throws OperationException {
    //                notificationManager.notify(
    //                    locale.failedToRename(), arg.getMessage(), FAIL, FLOAT_MODE);
    //              }
    //            });
  }

  private void applyChanges() {
    RenameSettings renameSettings = createRenameSettings();
    RenameParams renameParams = dtoFactory.createDto(RenameParams.class);
    renameParams.setNewName(view.getNewName());
    EditorPartPresenter activeEditor = editorAgent.getActiveEditor();
    VirtualFile file = activeEditor.getEditorInput().getFile();
    TextDocumentIdentifier textDocumentIdentifier = dtoBuildHelper.createTDI(file);
    renameParams.setTextDocument(textDocumentIdentifier);

    TextPosition cursorPosition = ((TextEditor) activeEditor).getCursorPosition();
    org.eclipse.lsp4j.Position position = dtoFactory.createDto(org.eclipse.lsp4j.Position.class);
    position.setCharacter(cursorPosition.getCharacter());
    position.setLine(cursorPosition.getLine());
    renameParams.setPosition(position);

    renameSettings.setRenameParams(renameParams);

    extensionServiceClient
        .rename(renameSettings)
        .then(this::applyRefactoring)
        .catchError(
            arg -> {
              notificationManager.notify(
                  locale.failedToRename(), arg.getMessage(), FAIL, FLOAT_MODE);
            });
  }

  private void applyRefactoring(WorkspaceEdit workspaceEdit) {
    //    refactorService
    //        .applyRefactoring(session)
    //        .then(
    //            refactoringResult -> {
    //              List<ChangeInfo> changes = refactoringResult.getChanges();
    //              if (refactoringResult.getSeverity() == RefactoringStatus.OK) {
    //                view.close();
    //                updateAfterRefactoring(changes)
    //                    .then(
    //                        refactoringUpdater
    //                            .handleMovingFiles(changes)
    //                            .then(clientServerEventService.sendFileTrackingResumeEvent()));
    //              } else {
    //                view.showErrorMessage(refactoringResult);
    //                refactoringUpdater
    //                    .handleMovingFiles(changes)
    //                    .then(clientServerEventService.sendFileTrackingResumeEvent());
    //              }
    //            });
  }

  private Promise<Void> updateAfterRefactoring(List<ChangeInfo> changes) {
    return refactoringUpdater
        .updateAfterRefactoring(changes)
        .then(
            arg -> {
              setEditorFocus();
            });
  }

  private RenameSettings createRenameSettings() {
    RenameSettings renameSettings = dtoFactory.createDto(RenameSettings.class);
    renameSettings.setDelegateUpdating(view.isUpdateDelegateUpdating());
    if (view.isUpdateDelegateUpdating()) {
      renameSettings.setDeprecateDelegates(view.isUpdateMarkDeprecated());
    }
    renameSettings.setUpdateSubpackages(view.isUpdateSubpackages());
    renameSettings.setUpdateReferences(view.isUpdateReferences());
    renameSettings.setUpdateQualifiedNames(view.isUpdateQualifiedNames());
    if (view.isUpdateQualifiedNames()) {
      renameSettings.setFilePatterns(view.getFilePatterns());
    }
    renameSettings.setUpdateTextualMatches(view.isUpdateTextualOccurrences());
    renameSettings.setUpdateSimilarDeclarations(view.isUpdateSimilarlyVariables());
    if (view.isUpdateSimilarlyVariables()) {
      renameSettings.setMachStrategy(
          similarNamesConfigurationPresenter.getMachStrategy().getValue());
    }

    return renameSettings;
  }
}
