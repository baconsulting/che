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
package org.eclipse.che.ide.ext.java.client.refactoring.preview;

import static java.util.stream.Collectors.toList;

import com.google.common.base.Optional;
import com.google.gwt.dom.client.Document;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.resources.Container;
import org.eclipse.che.ide.api.resources.File;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.java.client.refactoring.RefactorInfo;
import org.eclipse.che.ide.ext.java.client.refactoring.RefactoringActionDelegate;
import org.eclipse.che.ide.ext.java.shared.dto.refactoring.ChangePreview;
import org.eclipse.che.ide.resource.Path;
import org.eclipse.che.ide.rest.UrlBuilder;
import org.eclipse.che.jdt.ls.extension.api.dto.CheWorkspaceEdit;
import org.eclipse.che.plugin.languageserver.ide.editor.quickassist.ApplyWorkspaceEditAction;
import org.eclipse.lsp4j.ResourceChange;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * @author Dmitry Shnurenko
 * @author Valeriy Svydenko
 */
@Singleton
public class PreviewPresenter implements PreviewView.ActionDelegate {

  private final PreviewView view;
  private final AppContext appContext;
  private final ApplyWorkspaceEditAction applyWorkspaceEditAction;
  private final DtoFactory dtoFactory;

  private Map<String, PreviewNode> nodes;
  private CheWorkspaceEdit workspaceEdit;
  private RefactoringActionDelegate refactoringActionDelegate;

  @Inject
  public PreviewPresenter(
      PreviewView view,
      AppContext appContext,
      ApplyWorkspaceEditAction applyWorkspaceEditAction,
      DtoFactory dtoFactory) {
    this.view = view;
    this.appContext = appContext;
    this.applyWorkspaceEditAction = applyWorkspaceEditAction;
    this.dtoFactory = dtoFactory;
    this.view.setDelegate(this);
  }

  public void show(String refactoringSessionId, RefactorInfo refactorInfo) {
    view.showDialog();
  }

  /**
   * Set a title of the window.
   *
   * @param title the name of the preview window
   */
  public void setTitle(String title) {
    view.setTitleCaption(title);
  }

  /** {@inheritDoc} */
  @Override
  public void onCancelButtonClicked() {
    view.close();
  }

  /** {@inheritDoc} */
  @Override
  public void onAcceptButtonClicked() {
    updateFinalEdits();

    WorkspaceEdit edit = new WorkspaceEdit();
    edit.setChanges(workspaceEdit.getChanges());
    edit.setResourceChanges(new LinkedList<>());
    for (ResourceChange resourceChange : workspaceEdit.getResourceChanges()) {
      edit.getResourceChanges().add(Either.forLeft(resourceChange));
    }

    applyWorkspaceEditAction.applyWorkspaceEdit(edit);
    view.close();
    refactoringActionDelegate.closeWizard();
  }

  /** {@inheritDoc} */
  @Override
  public void onBackButtonClicked() {
    view.close();
  }

  @Override
  public void onSelectionChanged(PreviewNode selectedNode) {
    Either<ResourceChange, TextEdit> data = selectedNode.getData();
    if (data != null && data.isLeft()) {
      view.showDiff(null);
      return;
    }
    PreviewNode node = nodes.get(selectedNode.getUri());
    List<TextEdit> edits = new ArrayList<>();
    if (node.getId().equals(selectedNode.getId())) {
      for (PreviewNode child : node.getChildren()) {
        TextEdit right = child.getData().getRight();
        if (child.isEnable()) {
          edits.add(right);
        }
      }
    } else if (data != null && selectedNode.isEnable()) {
      edits.add(data.getRight());
    }
    Path path = toPath(selectedNode.getUri()).removeFirstSegments(1);
    Container workspaceRoot = appContext.getWorkspaceRoot();
    Promise<Optional<File>> file = workspaceRoot.getFile(path);
    file.then(
        arg -> {
          if (!arg.isPresent()) {
            return;
          }
          File existedFile = arg.get();
          existedFile
              .getContent()
              .then(
                  content -> {
                    ChangePreview changePreview = dtoFactory.createDto(ChangePreview.class);
                    changePreview.setFileName(existedFile.getName());
                    changePreview.setOldContent(content);

                    StringBuilder output = new StringBuilder();
                    new StringStreamEditor(edits, content, output).transform();
                    String result = output.toString();
                    changePreview.setNewContent(result);

                    view.showDiff(changePreview);
                  });
        });
  }

  @Override
  public void onEnabledStateChanged(PreviewNode change) {
    Either<ResourceChange, TextEdit> data = change.getData();
    if (data != null && data.isLeft()) {
      ResourceChange left = data.getLeft();
      nodes.get(left.getNewUri()).setEnable(change.isEnable());
    } else {
      PreviewNode previewNode = nodes.get(change.getUri());
      if (previewNode.getId().equals(change.getId())) {
        previewNode.setEnable(change.isEnable());
        for (PreviewNode node : previewNode.getChildren()) {
          node.setEnable(change.isEnable());
        }
      } else {
        for (PreviewNode node : previewNode.getChildren()) {
          if (node.getId().equals(change.getId())) {
            node.setEnable(change.isEnable());
          }
        }
      }
    }
  }

  public void show(
      CheWorkspaceEdit workspaceEdit, RefactoringActionDelegate refactoringActionDelegate) {
    this.workspaceEdit = workspaceEdit;
    this.refactoringActionDelegate = refactoringActionDelegate;
    nodes = new LinkedHashMap<>();

    prepareNodes(workspaceEdit);
    view.setTreeOfChanges(nodes);
    view.showDialog();
  }

  private void prepareNodes(CheWorkspaceEdit workspaceEdit) {
    prepareTextEditNodes(workspaceEdit.getChanges());
    prepareResourceChangeNodes(workspaceEdit.getResourceChanges());
  }

  private void prepareResourceChangeNodes(List<ResourceChange> resourceChanges) {
    for (ResourceChange resourceChange : resourceChanges) {
      PreviewNode node = new PreviewNode();
      node.setData(Either.forLeft(resourceChange));
      node.setEnable(true);
      String uniqueId = Document.get().createUniqueId();
      node.setId(uniqueId);
      String current = resourceChange.getCurrent();
      String newUri = resourceChange.getNewUri();
      node.setUri(newUri);
      if (current != null && newUri != null) {
        if (Path.valueOf(current)
            .removeLastSegments(1)
            .equals(Path.valueOf(newUri).removeLastSegments(1))) {
          node.setDescription(
              "Rename resource '"
                  + Path.valueOf(current).lastSegment()
                  + "' to '"
                  + Path.valueOf(newUri).lastSegment()
                  + "'");
        }
        // TODO need to set description for move operation
        nodes.put(newUri, node);
      }
    }
  }

  private void prepareTextEditNodes(Map<String, List<TextEdit>> changes) {
    for (String uri : changes.keySet()) {
      PreviewNode parent = new PreviewNode();
      parent.setUri(uri);
      parent.setEnable(true);
      String uniqueId = Document.get().createUniqueId();
      parent.setId(uniqueId);
      Path path = toPath(uri).removeFirstSegments(1);
      parent.setDescription(path.lastSegment() + " - " + path.removeLastSegments(1));
      nodes.put(uri, parent);
      for (TextEdit change : changes.get(uri)) {
        PreviewNode child = new PreviewNode();
        child.setEnable(true);
        child.setId(Document.get().createUniqueId());
        child.setDescription("Textual change");
        child.setData(Either.forRight(change));
        child.setUri(uri);
        parent.getChildren().add(child);
      }
    }
  }

  private void updateFinalEdits() {
    for (PreviewNode node : nodes.values()) {
      Either<ResourceChange, TextEdit> data = node.getData();
      if (data != null && data.isLeft()) {
        if (node.isEnable()) {
          continue;
        }
        ResourceChange left = data.getLeft();
        List<ResourceChange> selectedResourceChanges =
            workspaceEdit
                .getResourceChanges()
                .stream()
                .filter(item -> !item.equals(left))
                .collect(toList());
        workspaceEdit.setResourceChanges(selectedResourceChanges);
      } else {
        if (data == null && !node.isEnable()) {
          workspaceEdit.getChanges().remove(node.getUri());
          continue;
        }
        List<PreviewNode> children = node.getChildren();
        for (PreviewNode textNode : children) {
          if (textNode.isEnable()) {
            continue;
          }
          TextEdit right = textNode.getData().getRight();
          List<TextEdit> textNodes =
              workspaceEdit
                  .getChanges()
                  .get(node.getUri())
                  .stream()
                  .filter(item -> !item.equals(right))
                  .collect(toList());

          workspaceEdit.getChanges().put(node.getUri(), textNodes);
        }
      }
    }
  }

  private Path toPath(String uri) {
    return uri.startsWith("/") ? new Path(uri) : new Path(new UrlBuilder(uri).getPath());
  }
}
