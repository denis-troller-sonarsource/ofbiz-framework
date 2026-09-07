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

const nock = require('nock');
const request = require('supertest');

const MONOLITH_TARGET_URL = 'http://monolith.test';

describe('gateway app', () => {
  let app;

  beforeAll(() => {
    process.env.MONOLITH_TARGET_URL = MONOLITH_TARGET_URL;
    jest.resetModules();
    // eslint-disable-next-line global-require
    app = require('../src/app');
  });

  afterEach(() => {
    nock.cleanAll();
  });

  afterAll(() => {
    delete process.env.MONOLITH_TARGET_URL;
    nock.restore ? undefined : nock.cleanAll();
  });

  test('GET /health returns 200 ok without touching the monolith', async () => {
    const scope = nock(MONOLITH_TARGET_URL).get('/health').reply(200);

    const res = await request(app).get('/health');

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok' });
    expect(scope.isDone()).toBe(false);
  });

  test('proxies a GET request to the monolith unchanged, path and query string preserved', async () => {
    const scope = nock(MONOLITH_TARGET_URL)
      .get('/catalog/control/main')
      .query({ category: 'shoes' })
      .reply(200, { hello: 'world' }, { 'content-type': 'application/json' });

    const res = await request(app).get('/catalog/control/main?category=shoes');

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ hello: 'world' });
    expect(scope.isDone()).toBe(true);
  });

  test('proxies a POST request with a JSON body unchanged', async () => {
    const payload = { orderId: '12345' };
    const scope = nock(MONOLITH_TARGET_URL)
      .post('/ordermgr/control/createOrder', payload)
      .reply(201, { created: true });

    const res = await request(app)
      .post('/ordermgr/control/createOrder')
      .send(payload);

    expect(res.status).toBe(201);
    expect(res.body).toEqual({ created: true });
    expect(scope.isDone()).toBe(true);
  });

  test('preserves non-2xx status codes and response bodies from the monolith', async () => {
    const scope = nock(MONOLITH_TARGET_URL)
      .get('/partymgr/control/missing')
      .reply(404, { error: 'not found' });

    const res = await request(app).get('/partymgr/control/missing');

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'not found' });
    expect(scope.isDone()).toBe(true);
  });

  test('forwards custom request headers to the monolith', async () => {
    const scope = nock(MONOLITH_TARGET_URL)
      .matchHeader('x-correlation-id', 'abc-123')
      .get('/webtools/control/main')
      .reply(200, {});

    const res = await request(app)
      .get('/webtools/control/main')
      .set('x-correlation-id', 'abc-123');

    expect(res.status).toBe(200);
    expect(scope.isDone()).toBe(true);
  });
});
