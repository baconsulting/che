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
package org.eclipse.che.ide.ext.java.client.refactoring.rename;

import static org.eclipse.che.ide.api.editor.events.FileEvent.FileOperation.CLOSE;
import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.ide.api.editor.EditorWithAutoSave;
import org.eclipse.che.ide.api.editor.events.FileEvent;
import org.eclipse.che.ide.api.editor.events.FileEvent.FileEventHandler;
import org.eclipse.che.ide.api.editor.link.HasLinkedMode;
import org.eclipse.che.ide.api.editor.link.LinkedMode;
import org.eclipse.che.ide.api.editor.link.LinkedModel;
import org.eclipse.che.ide.api.editor.link.LinkedModelData;
import org.eclipse.che.ide.api.editor.link.LinkedModelGroup;
import org.eclipse.che.ide.api.editor.text.Position;
import org.eclipse.che.ide.api.editor.text.TextPosition;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.editor.texteditor.UndoableEditor;
import org.eclipse.che.ide.api.filewatcher.ClientServerEventService;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.java.client.JavaLocalizationConstant;
import org.eclipse.che.ide.ext.java.client.refactoring.RefactorInfo;
import org.eclipse.che.ide.ext.java.client.refactoring.RefactoringUpdater;
import org.eclipse.che.ide.ext.java.client.refactoring.move.RefactoredItemType;
import org.eclipse.che.ide.ext.java.client.refactoring.rename.wizard.RenamePresenter;
import org.eclipse.che.ide.ext.java.client.service.JavaLanguageExtensionServiceClient;
import org.eclipse.che.ide.ui.dialogs.DialogFactory;
import org.eclipse.che.jdt.ls.extension.api.dto.LinkedData;
import org.eclipse.che.jdt.ls.extension.api.dto.LinkedModeModel;
import org.eclipse.che.jdt.ls.extension.api.dto.LinkedModelParams;
import org.eclipse.che.jdt.ls.extension.api.dto.LinkedPositionGroup;
import org.eclipse.che.jdt.ls.extension.api.dto.Region;
import org.eclipse.che.jdt.ls.extension.api.dto.RenameSettings;
import org.eclipse.che.plugin.languageserver.ide.util.DtoBuildHelper;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * Class for rename refactoring java classes
 *
 * @author Alexander Andrienko
 * @author Valeriy Svydenko
 */
@Singleton
public class JavaRefactoringRename implements FileEventHandler {
  private final RenamePresenter renamePresenter;
  private DtoBuildHelper dtoBuildHelper;
  private final RefactoringUpdater refactoringUpdater;
  private final JavaLocalizationConstant locale;
  private final DtoFactory dtoFactory;
  private JavaLanguageExtensionServiceClient extensionServiceClient;
  private final ClientServerEventService clientServerEventService;
  private final DialogFactory dialogFactory;
  private final NotificationManager notificationManager;

  private boolean isActiveLinkedEditor;
  private TextEditor textEditor;
  private LinkedMode mode;
  private HasLinkedMode linkedEditor;
  private String newName;
  private TextPosition cursorPosition;

  @Inject
  public JavaRefactoringRename(
      RenamePresenter renamePresenter,
      DtoBuildHelper dtoBuildHelper,
      RefactoringUpdater refactoringUpdater,
      JavaLocalizationConstant locale,
      JavaLanguageExtensionServiceClient extensionServiceClient,
      ClientServerEventService clientServerEventService,
      DtoFactory dtoFactory,
      EventBus eventBus,
      DialogFactory dialogFactory,
      NotificationManager notificationManager) {
    this.renamePresenter = renamePresenter;
    this.dtoBuildHelper = dtoBuildHelper;
    this.refactoringUpdater = refactoringUpdater;
    this.locale = locale;
    this.extensionServiceClient = extensionServiceClient;
    this.clientServerEventService = clientServerEventService;
    this.dialogFactory = dialogFactory;
    this.dtoFactory = dtoFactory;
    this.notificationManager = notificationManager;

    isActiveLinkedEditor = false;

    eventBus.addHandler(FileEvent.TYPE, this);
  }

  /**
   * Launch java rename refactoring process
   *
   * @param textEditorPresenter editor where user makes refactoring
   */
  public void refactor(final TextEditor textEditorPresenter) {
    if (!(textEditorPresenter instanceof HasLinkedMode)) {
      return;
    }

    if (isActiveLinkedEditor) {
      if (mode != null) {
        mode.exitLinkedMode(false);
      }
      renamePresenter.show(RefactorInfo.of(RefactoredItemType.JAVA_ELEMENT, null));
      isActiveLinkedEditor = false;
    } else {
      isActiveLinkedEditor = true;
      textEditor = textEditorPresenter;
      createLinkedRename();
    }

    linkedEditor = (HasLinkedMode) textEditorPresenter;
    textEditorPresenter.setFocus();
  }

  private void showError() {
    dialogFactory
        .createMessageDialog(locale.renameRename(), locale.renameOperationUnavailable(), null)
        .show();
    if (mode != null) {
      mode.exitLinkedMode(false);
    }
  }

  private void createLinkedRename() {
    cursorPosition = textEditor.getCursorPosition();
    LinkedModelParams params = dtoFactory.createDto(LinkedModelParams.class);
    params.setUri(textEditor.getDocument().getFile().getLocation().toString());
    params.setOffset(textEditor.getCursorOffset());

    extensionServiceClient
        .getLinkedModeModel(params)
        .then(
            linkedModeModel -> {
              clientServerEventService
                  .sendFileTrackingSuspendEvent()
                  .then(
                      success -> {
                        if (linkedModeModel == null) {
                          showError();
                          isActiveLinkedEditor = false;
                          return;
                        }
                        activateLinkedModeIntoEditor(linkedModeModel);
                      });
            })
        .catchError(
            arg -> {
              isActiveLinkedEditor = false;
              showError();
            });
  }

  @Override
  public void onFileOperation(FileEvent event) {
    if (event.getOperationType() == CLOSE
        && textEditor != null
        && textEditor.getDocument() != null
        && textEditor.getDocument().getFile().getLocation().equals(event.getFile().getLocation())) {
      isActiveLinkedEditor = false;
    }
  }

  /** returns {@code true} if linked editor is activated. */
  public boolean isActiveLinkedEditor() {
    return isActiveLinkedEditor;
  }

  private void activateLinkedModeIntoEditor(LinkedModeModel linkedModeModel) {
    mode = linkedEditor.getLinkedMode();
    LinkedModel model = linkedEditor.createLinkedModel();
    List<LinkedModelGroup> groups = new ArrayList<>();
    for (LinkedPositionGroup positionGroup : linkedModeModel.getGroups()) {
      LinkedModelGroup group = linkedEditor.createLinkedGroup();
      LinkedData data = positionGroup.getLinkedData();
      if (data != null) {
        LinkedModelData modelData = linkedEditor.createLinkedModelData();
        modelData.setType("link");
        modelData.setValues(data.getValues());
        group.setData(modelData);
      }
      List<Position> positions = new ArrayList<>();
      for (Region region : positionGroup.getPositions()) {
        positions.add(new Position(region.getOffset(), region.getLength()));
      }
      group.setPositions(positions);
      groups.add(group);
    }
    model.setGroups(groups);
    disableAutoSave();

    mode.enterLinkedMode(model);

    mode.addListener(
        new LinkedMode.LinkedModeListener() {
          @Override
          public void onLinkedModeExited(boolean successful, int start, int end) {
            boolean isSuccessful = false;
            try {
              if (successful) {
                isSuccessful = true;
                newName = textEditor.getDocument().getContentRange(start, end - start);
                performRename(newName);
              }
            } finally {
              mode.removeListener(this);

              isActiveLinkedEditor = false;

              boolean isNameChanged = start >= 0 && end >= 0;
              if (!isSuccessful && isNameChanged) {
                undoChanges();
              }

              if (!isSuccessful) {
                clientServerEventService
                    .sendFileTrackingResumeEvent()
                    .then(
                        arg -> {
                          enableAutoSave();
                        });
              }
            }
          }
        });
  }

  private void performRename(String newName) {
    RenameSettings settings = dtoFactory.createDto(RenameSettings.class);

    RenameParams renameParams = dtoFactory.createDto(RenameParams.class);
    renameParams.setNewName(newName);
    VirtualFile file = textEditor.getEditorInput().getFile();
    TextDocumentIdentifier textDocumentIdentifier = dtoBuildHelper.createTDI(file);
    renameParams.setTextDocument(textDocumentIdentifier);

    org.eclipse.lsp4j.Position position = dtoFactory.createDto(org.eclipse.lsp4j.Position.class);
    position.setCharacter(cursorPosition.getCharacter());
    position.setLine(cursorPosition.getLine());
    renameParams.setPosition(position);

    settings.setUpdateReferences(true);
    settings.setRenameParams(renameParams);

    extensionServiceClient
        .rename(settings)
        .then(
            edits -> {
              enableAutoSave();
              // TODO refactoringUpdater.updateAfterRefactoring(changes)
              clientServerEventService.sendFileTrackingResumeEvent();
            })
        .catchError(
            new Operation<PromiseError>() {
              @Override
              public void apply(PromiseError error) throws OperationException {
                undoChanges();
                enableAutoSave();
                // TODO
                clientServerEventService.sendFileTrackingResumeEvent();
                notificationManager.notify(
                    locale.failedToRename(), error.getMessage(), FAIL, FLOAT_MODE);
              }
            });
  }

  private void enableAutoSave() {
    if (linkedEditor instanceof EditorWithAutoSave) {
      ((EditorWithAutoSave) linkedEditor).enableAutoSave();
    }
  }

  private void disableAutoSave() {
    if (linkedEditor instanceof EditorWithAutoSave) {
      ((EditorWithAutoSave) linkedEditor).disableAutoSave();
    }
  }

  private void undoChanges() {
    if (linkedEditor instanceof UndoableEditor) {
      ((UndoableEditor) linkedEditor).getUndoRedo().undo();
    }
  }
}
