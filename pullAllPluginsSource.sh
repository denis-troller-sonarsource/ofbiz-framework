#!/usr/bin/env sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Syntax: ./pullAllPluginsSource.sh

# Not needed, see https://docs.github.com/en/actions/using-workflows/workflow-syntax-for-github-actions#exit-codes-and-error-action-preference
# set -e

# Whatever, create anew
if [ -d "plugins" ]
    then
        rm -rf plugins
fi

PLUGINS_REPO="https://github.com/apache/ofbiz-plugins.git"

# Get the branch used in framework
branch=$(git branch --show-current)

# Fall back to trunk when the current branch has no counterpart in ofbiz-plugins
# (e.g. a feature branch created only in this repository).
if ! git ls-remote --exit-code --heads "$PLUGINS_REPO" "$branch" > /dev/null 2>&1
    then
        branch=trunk
fi

git clone --depth 1 --single-branch --branch $branch "$PLUGINS_REPO" plugins

# remove .git, in this case it's useless information
 if [ -d "plugins" ]
     then
        rm -rf plugins/.git
fi
