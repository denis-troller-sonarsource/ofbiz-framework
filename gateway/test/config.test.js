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

describe('config', () => {
  const ORIGINAL_ENV = process.env;

  beforeEach(() => {
    jest.resetModules();
    process.env = { ...ORIGINAL_ENV };
    delete process.env.PORT;
    delete process.env.MONOLITH_TARGET_URL;
  });

  afterAll(() => {
    process.env = ORIGINAL_ENV;
  });

  test('defaults PORT to 3000 when not set', () => {
    const config = require('../src/config');
    expect(config.port).toBe(3000);
  });

  test('defaults MONOLITH_TARGET_URL to https://localhost:8443 when not set', () => {
    const config = require('../src/config');
    expect(config.monolithTargetUrl).toBe('https://localhost:8443');
  });

  test('reads PORT from the environment when set', () => {
    process.env.PORT = '4001';
    const config = require('../src/config');
    expect(config.port).toBe(4001);
  });

  test('reads MONOLITH_TARGET_URL from the environment when set', () => {
    process.env.MONOLITH_TARGET_URL = 'http://example.internal:9999';
    const config = require('../src/config');
    expect(config.monolithTargetUrl).toBe('http://example.internal:9999');
  });
});
