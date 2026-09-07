/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

'use strict';

const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');

const config = require('./config');

function createApp() {
  const app = express();

  // Gateway's own health check, served locally and never proxied to the
  // monolith - this is what later Phase 0 observability wiring hooks into.
  app.get('/health', (req, res) => {
    res.status(200).json({ status: 'ok' });
  });

  app.use(
    '/',
    createProxyMiddleware({
      target: config.monolithTargetUrl,
      changeOrigin: true,
      secure: false,
    })
  );

  return app;
}

module.exports = createApp();
