/*******************************************************************************
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
 *******************************************************************************/
package org.apache.ofbiz.shipment.picklist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.util.EntityFindOptions;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.junit.jupiter.api.Test;

class PickListServicesTest {

    private static Delegator mockDelegator() {
        Delegator delegator = mock(Delegator.class);
        when(delegator.getDelegator()).thenReturn(delegator);
        return delegator;
    }

    private static DispatchContext mockDispatchContext(Delegator delegator) {
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        return dctx;
    }

    @Test
    void convertOrderIdListToHeadersWithNullOrderIdListReturnsNullHeaderList() {
        DispatchContext dctx = mockDispatchContext(mockDelegator());
        Map<String, Object> context = new HashMap<>();
        context.put("orderIdList", null);
        context.put("orderHeaderList", null);

        Map<String, Object> result = PickListServices.convertOrderIdListToHeaders(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals(null, result.get("orderHeaderList"));
    }

    @Test
    void convertOrderIdListToHeadersWithExistingHeaderListSkipsQuery() {
        Delegator delegator = mockDelegator();
        DispatchContext dctx = mockDispatchContext(delegator);
        GenericValue existingHeader = mock(GenericValue.class);
        Map<String, Object> context = new HashMap<>();
        context.put("orderIdList", List.of("ORDER1"));
        context.put("orderHeaderList", List.of(existingHeader));

        Map<String, Object> result = PickListServices.convertOrderIdListToHeaders(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals(List.of(existingHeader), result.get("orderHeaderList"));
    }

    @Test
    void convertOrderIdListToHeadersQueriesApprovedSalesOrdersForGivenIds() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue orderHeader = mock(GenericValue.class);
        when(delegator.findList(eq("OrderHeader"), any(EntityCondition.class), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(orderHeader));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("orderIdList", List.of("ORDER1", "ORDER2"));
        context.put("orderHeaderList", null);

        Map<String, Object> result = PickListServices.convertOrderIdListToHeaders(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals(List.of(orderHeader), result.get("orderHeaderList"));
    }

    @Test
    void convertOrderIdListToHeadersReturnsErrorWhenQueryFails() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenThrow(new org.apache.ofbiz.entity.GenericEntityException("boom"));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("orderIdList", List.of("ORDER1"));
        context.put("orderHeaderList", null);

        Map<String, Object> result = PickListServices.convertOrderIdListToHeaders(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void isBinCompleteReturnsTrueWhenNoIncompleteItemsRemain() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findCountByCondition(eq("PicklistItem"), any(), any(), any(), any())).thenReturn(0L);

        boolean complete = PickListServices.isBinComplete(delegator, "BIN1");

        assertTrue(complete);
    }

    @Test
    void isBinCompleteReturnsFalseWhenIncompleteItemsRemain() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findCountByCondition(eq("PicklistItem"), any(), any(), any(), any())).thenReturn(3L);

        boolean complete = PickListServices.isBinComplete(delegator, "BIN1");

        assertFalse(complete);
    }

    @Test
    void isBinCompletePropagatesEntityException() {
        Delegator delegator = mockDelegator();

        org.junit.jupiter.api.function.Executable call = () -> {
            when(delegator.findCountByCondition(eq("PicklistItem"), any(), any(), any(), any()))
                    .thenThrow(new org.apache.ofbiz.entity.GenericEntityException("boom"));
            PickListServices.isBinComplete(delegator, "BIN1");
        };

        org.junit.jupiter.api.Assertions.assertThrows(org.apache.ofbiz.entity.GenericEntityException.class, call);
    }
}
