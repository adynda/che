/*
 * Copyright (c) 2015-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 */
'use strict';

/**
 * @ngdoc directive
 * @name workspaces.details.directive:shareWorkspace
 * @restrict E
 * @element
 *
 * @description
 * <share-workspace></share-workspace> for managing sharing the workspace.
 *
 * @usage
 *   <share-workspace></share-workspace>
 *
 * @author Ann Shumilova
 */
export class ShareWorkspace {

  /**
   * Default constructor that is using resource
   * @ngInject for Dependency injection
   */
  constructor () {
    this.restrict = 'E';
    this.templateUrl = 'app/workspaces/workspace-details/share-workspace/share-workspace.html';

    this.controller = 'ShareWorkspaceController';
    this.controllerAs = 'shareWorkspaceController';
    this.bindToController = true;
  }
}
