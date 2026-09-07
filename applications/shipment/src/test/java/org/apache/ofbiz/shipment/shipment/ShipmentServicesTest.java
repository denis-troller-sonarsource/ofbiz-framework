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
package org.apache.ofbiz.shipment.shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityFindOptions;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.junit.jupiter.api.Test;

class ShipmentServicesTest {

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
    void removeShipmentEstimateDeletesFoundEstimate() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue estimate = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentCostEstimate"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(estimate));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentCostEstimateId", "EST1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.removeShipmentEstimate(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        verify(estimate, times(1)).remove();
    }

    @Test
    void removeShipmentEstimateReturnsErrorWhenLookupFails() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenThrow(new GenericEntityException("boom"));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentCostEstimateId", "EST1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.removeShipmentEstimate(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void calcShipmentCostEstimateReturnsZeroWhenNoEstimatesFound() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("ShipmentCostEstimate"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        DispatchContext dctx = mockDispatchContext(delegator);

        Map<String, Object> context = new HashMap<>();
        context.put("locale", Locale.US);
        context.put("productStoreId", "STORE1");
        context.put("carrierRoleTypeId", "CARRIER");
        context.put("carrierPartyId", "PARTY1");
        context.put("shipmentMethodTypeId", "STANDARD");
        context.put("initialEstimateAmt", BigDecimal.TEN);

        Map<String, Object> result = ShipmentServices.calcShipmentCostEstimate(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals(BigDecimal.ZERO, result.get("shippingEstimateAmount"));
    }

    @Test
    void calcShipmentCostEstimateReturnsErrorWhenQueryFails() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenThrow(new GenericEntityException("boom"));
        DispatchContext dctx = mockDispatchContext(delegator);

        Map<String, Object> context = new HashMap<>();
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.calcShipmentCostEstimate(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void getShipmentGatewayConfigFromShipmentReturnsErrorWhenShipmentNotFound() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());

        Map<String, Object> result = ShipmentServices.getShipmentGatewayConfigFromShipment(delegator, "SHIP1", Locale.US);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void getShipmentGatewayConfigFromShipmentReturnsErrorWhenPrimaryOrderHeaderMissing() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getRelatedOne("PrimaryOrderHeader", false)).thenReturn(null);
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));

        Map<String, Object> result = ShipmentServices.getShipmentGatewayConfigFromShipment(delegator, "SHIP1", Locale.US);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void getShipmentGatewayConfigFromShipmentReturnsConfigWhenFound() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        GenericValue primaryOrderHeader = mock(GenericValue.class);
        when(primaryOrderHeader.getString("productStoreId")).thenReturn("STORE1");
        GenericValue primaryOrderItemShipGroup = mock(GenericValue.class);
        when(primaryOrderItemShipGroup.getString("shipmentMethodTypeId")).thenReturn("STANDARD");
        when(primaryOrderItemShipGroup.getString("carrierPartyId")).thenReturn("PARTY1");
        when(primaryOrderItemShipGroup.getString("carrierRoleTypeId")).thenReturn("CARRIER");

        when(shipment.getRelatedOne("PrimaryOrderHeader", false)).thenReturn(primaryOrderHeader);
        when(shipment.getRelatedOne("PrimaryOrderItemShipGroup", false)).thenReturn(primaryOrderItemShipGroup);
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));

        GenericValue productStoreShipmentMeth = mock(GenericValue.class);
        when(productStoreShipmentMeth.getString("shipmentGatewayConfigId")).thenReturn("CONFIG1");
        when(productStoreShipmentMeth.getString("configProps")).thenReturn("props");
        when(delegator.findList(eq("ProductStoreShipmentMeth"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(productStoreShipmentMeth));

        Map<String, Object> result = ShipmentServices.getShipmentGatewayConfigFromShipment(delegator, "SHIP1", Locale.US);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals("CONFIG1", result.get("shipmentGatewayConfigId"));
        assertEquals("props", result.get("configProps"));
    }

    @Test
    void getShipmentGatewayConfigFromShipmentReturnsErrorOnEntityException() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenThrow(new GenericEntityException("boom"));

        Map<String, Object> result = ShipmentServices.getShipmentGatewayConfigFromShipment(delegator, "SHIP1", Locale.US);

        assertTrue(ServiceUtil.isError(result));
    }
}
