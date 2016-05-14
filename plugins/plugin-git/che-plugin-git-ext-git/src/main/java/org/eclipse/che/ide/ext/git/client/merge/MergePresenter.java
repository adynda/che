/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.ext.git.client.merge;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.api.core.ErrorCodes;
import org.eclipse.che.ide.api.git.GitServiceClient;
import org.eclipse.che.api.git.shared.Branch;
import org.eclipse.che.api.git.shared.MergeResult;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.workspace.Workspace;
import org.eclipse.che.ide.commons.exception.ServerException;
import org.eclipse.che.ide.ext.git.client.GitLocalizationConstant;
import org.eclipse.che.ide.ext.git.client.outputconsole.GitOutputConsole;
import org.eclipse.che.ide.ext.git.client.outputconsole.GitOutputConsoleFactory;
import org.eclipse.che.ide.extension.machine.client.processes.ConsolesPanelPresenter;
import org.eclipse.che.ide.api.dialogs.ConfirmCallback;
import org.eclipse.che.ide.api.dialogs.DialogFactory;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

import static org.eclipse.che.api.git.shared.BranchListRequest.LIST_LOCAL;
import static org.eclipse.che.api.git.shared.BranchListRequest.LIST_REMOTE;
import static org.eclipse.che.api.git.shared.MergeResult.MergeStatus.ALREADY_UP_TO_DATE;
import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;
import static org.eclipse.che.ide.ext.git.client.merge.Reference.RefType.LOCAL_BRANCH;
import static org.eclipse.che.ide.ext.git.client.merge.Reference.RefType.REMOTE_BRANCH;

/**
 * Presenter to perform merge reference with current HEAD commit.
 *
 * @author Ann Zhuleva
 * @author Vlad Zhukovskyi
 */
@Singleton
public class MergePresenter implements MergeView.ActionDelegate {
    public static final String MERGE_COMMAND_NAME    = "Git merge";
    public static final String LOCAL_BRANCHES_TITLE  = "Local Branches";
    public static final String REMOTE_BRANCHES_TITLE = "Remote Branches";

    private final MergeView                view;
    private final DialogFactory            dialogFactory;
    private final GitOutputConsoleFactory  gitOutputConsoleFactory;
    private final ConsolesPanelPresenter   consolesPanelPresenter;
    private final Workspace                workspace;
    private final GitServiceClient         service;
    private final GitLocalizationConstant  constant;
    private final AppContext               appContext;
    private final NotificationManager      notificationManager;

    private Reference selectedReference;
    private Project project;

    @Inject
    public MergePresenter(MergeView view,
                          GitServiceClient service,
                          GitLocalizationConstant constant,
                          AppContext appContext,
                          NotificationManager notificationManager,
                          DialogFactory dialogFactory,
                          GitOutputConsoleFactory gitOutputConsoleFactory,
                          ConsolesPanelPresenter consolesPanelPresenter,
                          Workspace workspace) {
        this.view = view;
        this.dialogFactory = dialogFactory;
        this.gitOutputConsoleFactory = gitOutputConsoleFactory;
        this.consolesPanelPresenter = consolesPanelPresenter;
        this.workspace = workspace;
        this.view.setDelegate(this);
        this.service = service;
        this.constant = constant;
        this.appContext = appContext;
        this.notificationManager = notificationManager;

    }

    /** Show dialog. */
    public void showDialog(Project project) {
        this.project = project;
        final GitOutputConsole console = gitOutputConsoleFactory.create(MERGE_COMMAND_NAME);
        selectedReference = null;
        view.setEnableMergeButton(false);

        service.branchList(appContext.getDevMachine(), project.getLocation(), LIST_LOCAL).then(new Operation<List<Branch>>() {
            @Override
            public void apply(List<Branch> branches) throws OperationException {
                List<Reference> references = new ArrayList<>();
                for (Branch branch : branches) {
                    if (!branch.isActive()) {
                        Reference reference = new Reference(branch.getName(), branch.getDisplayName(), LOCAL_BRANCH);
                        references.add(reference);
                    }
                }
                view.setLocalBranches(references);
            }
        }).catchError(new Operation<PromiseError>() {
            @Override
            public void apply(PromiseError error) throws OperationException {
                console.printError(error.getMessage());
                consolesPanelPresenter.addCommandOutput(appContext.getDevMachine().getId(), console);
                notificationManager.notify(constant.branchesListFailed(), FAIL, FLOAT_MODE);
            }
        });

        service.branchList(appContext.getDevMachine(), project.getLocation(), LIST_REMOTE).then(new Operation<List<Branch>>() {
            @Override
            public void apply(List<Branch> branches) throws OperationException {
                List<Reference> references = new ArrayList<>();
                for (Branch branch : branches) {
                    if (!branch.isActive()) {
                        Reference reference =
                                new Reference(branch.getName(), branch.getDisplayName(), REMOTE_BRANCH);
                        references.add(reference);
                    }
                }
                view.setRemoteBranches(references);
            }
        }).catchError(new Operation<PromiseError>() {
            @Override
            public void apply(PromiseError error) throws OperationException {
                console.printError(error.getMessage());
                consolesPanelPresenter.addCommandOutput(appContext.getDevMachine().getId(), console);
                notificationManager.notify(constant.branchesListFailed(), FAIL, FLOAT_MODE);
            }
        });

        view.showDialog();
    }

    /** {@inheritDoc} */
    @Override
    public void onCancelClicked() {
        view.close();
    }

    /** {@inheritDoc} */
    @Override
    public void onMergeClicked() {
        view.close();

        final GitOutputConsole console = gitOutputConsoleFactory.create(MERGE_COMMAND_NAME);

        service.merge(appContext.getDevMachine(), project.getLocation(), selectedReference.getDisplayName()).then(new Operation<MergeResult>() {
            @Override
            public void apply(MergeResult result) throws OperationException {
                console.print(formMergeMessage(result));
                consolesPanelPresenter.addCommandOutput(appContext.getDevMachine().getId(), console);
                notificationManager.notify(formMergeMessage(result));

                project.synchronize();
            }
        }).catchError(new Operation<PromiseError>() {
            @Override
            public void apply(PromiseError error) throws OperationException {
                if (error.getCause() instanceof ServerException &&
                    ((ServerException)error.getCause()).getErrorCode() == ErrorCodes.NO_COMMITTER_NAME_OR_EMAIL_DEFINED) {
                    dialogFactory.createMessageDialog(constant.mergeTitle(), constant.committerIdentityInfoEmpty(),
                                                      new ConfirmCallback() {
                                                          @Override
                                                          public void accepted() {
                                                              //do nothing
                                                          }
                                                      }).show();
                    return;
                }
                console.printError(error.getMessage());
                consolesPanelPresenter.addCommandOutput(appContext.getDevMachine().getId(), console);
                notificationManager.notify(constant.mergeFailed(), FAIL, FLOAT_MODE);
            }
        });
    }

    /**
     * Form the result message of the merge operation.
     *
     * @param mergeResult
     *         result of merge operation
     * @return {@link String} merge result message
     */
    @NotNull
    private String formMergeMessage(@NotNull MergeResult mergeResult) {
        if (mergeResult.getMergeStatus().equals(ALREADY_UP_TO_DATE)) {
            return mergeResult.getMergeStatus().getValue();
        }

        StringBuilder conflictMessage = new StringBuilder();
        List<String> conflicts = mergeResult.getConflicts();
        if (conflicts != null && conflicts.size() > 0) {
            for (String conflict : conflicts) {
                conflictMessage.append("- ").append(conflict);
            }
        }
        StringBuilder commitsMessage = new StringBuilder();
        List<String> commits = mergeResult.getMergedCommits();
        if (commits != null && commits.size() > 0) {
            for (String commit : commits) {
                commitsMessage.append("- ").append(commit);
            }
        }

        String message = "<b>" + mergeResult.getMergeStatus().getValue() + "</b>";
        String conflictText = conflictMessage.toString();
        message += (!conflictText.isEmpty()) ? constant.mergedConflicts() : "";


        String commitText = commitsMessage.toString();
        message += (!commitText.isEmpty()) ? " " + constant.mergedCommits(commitText) : "";
        message += (mergeResult.getNewHead() != null) ? " " + constant.mergedNewHead(mergeResult.getNewHead()) : "";
        return message;
    }

    /** {@inheritDoc} */
    @Override
    public void onReferenceSelected(@NotNull Reference reference) {
        selectedReference = reference;
        String displayName = selectedReference.getDisplayName();
        boolean isEnabled = !displayName.equals(LOCAL_BRANCHES_TITLE) && !displayName.equals(REMOTE_BRANCHES_TITLE);
        view.setEnableMergeButton(isEnabled);
    }
}
