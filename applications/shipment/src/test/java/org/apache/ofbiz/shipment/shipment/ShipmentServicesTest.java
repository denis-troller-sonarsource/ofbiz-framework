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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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
import org.apache.ofbiz.entity.DelegatorFactory;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.util.EntityFindOptions;
import org.apache.ofbiz.entity.util.EntityListIterator;
import org.apache.ofbiz.party.party.PartyWorker;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ShipmentServicesTest {

    private static Delegator mockDelegator() {
        Delegator delegator = mock(Delegator.class);
        when(delegator.getDelegator()).thenReturn(delegator);
        // EntityQuery.queryOne() builds a GenericPK via delegator.getModelEntity(entityName) when
        // called with a Map where-clause; stub a bare ModelEntity so that path doesn't NPE.
        ModelEntity modelEntity = mock(ModelEntity.class);
        when(modelEntity.getEntityName()).thenReturn("Mocked");
        when(delegator.getModelEntity(anyString())).thenReturn(modelEntity);
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

    @Test
    void fillShipmentStagingTablesReturnsErrorWhenShipmentNotFound() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.fillShipmentStagingTables(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void fillShipmentStagingTablesSkipsStagingWhenShipmentNotPacked() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("statusId")).thenReturn("SHIPMENT_INPUT");
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.fillShipmentStagingTables(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
    }

    @Test
    void fillShipmentStagingTablesReturnsErrorWhenAddressMissing() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("statusId")).thenReturn("SHIPMENT_PACKED");
        when(shipment.getRelatedOne("DestinationPostalAddress", false)).thenReturn(null);
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.fillShipmentStagingTables(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void fillShipmentStagingTablesReturnsErrorWhenNoPackagesAvailable() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        GenericValue address = mock(GenericValue.class);
        when(shipment.getString("statusId")).thenReturn("SHIPMENT_PACKED");
        when(shipment.getRelatedOne("DestinationPostalAddress", false)).thenReturn(address);
        when(shipment.getRelated("ShipmentPackage", null, null, false)).thenReturn(List.of());
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.fillShipmentStagingTables(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void fillShipmentStagingTablesStoresStagingRecordsWhenPacked() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("statusId")).thenReturn("SHIPMENT_PACKED");
        GenericValue address = mock(GenericValue.class);
        when(shipment.getRelatedOne("DestinationPostalAddress", false)).thenReturn(address);
        GenericValue shipmentPackage = mock(GenericValue.class);
        when(shipment.getRelated("ShipmentPackage", null, null, false)).thenReturn(List.of(shipmentPackage));
        GenericValue routeSeg = mock(GenericValue.class);
        when(shipment.getRelated("ShipmentRouteSegment", null, null, false)).thenReturn(List.of(routeSeg));
        GenericValue stageShip = mock(GenericValue.class);
        GenericValue stagePkg = mock(GenericValue.class);
        when(delegator.makeValue("OdbcShipmentOut")).thenReturn(stageShip);
        when(delegator.makeValue("OdbcPackageOut")).thenReturn(stagePkg);
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.fillShipmentStagingTables(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        verify(delegator, times(1)).storeAll(any());
    }

    @Test
    void clearShipmentStagingInfoRemovesStagingRecords() throws Exception {
        Delegator delegator = mockDelegator();
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");

        Map<String, Object> result = ShipmentServices.clearShipmentStagingInfo(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        verify(delegator, times(1)).removeByAnd(eq("OdbcPackageIn"), any(Map.class));
        verify(delegator, times(1)).removeByAnd(eq("OdbcPackageOut"), any(Map.class));
        verify(delegator, times(1)).removeByAnd(eq("OdbcShipmentOut"), any(Map.class));
    }

    @Test
    void clearShipmentStagingInfoReturnsErrorOnEntityException() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.removeByAnd(anyString(), any(Map.class))).thenThrow(new GenericEntityException("boom"));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");

        Map<String, Object> result = ShipmentServices.clearShipmentStagingInfo(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void updatePurchaseShipmentFromReceiptReturnsSuccessWhenNoReceiptsExist() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("ShipmentReceipt"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));

        Map<String, Object> result = ShipmentServices.updatePurchaseShipmentFromReceipt(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
    }

    @Test
    void updatePurchaseShipmentFromReceiptReturnsSuccessWhenNoShippedItems() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue receipt = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentReceipt"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(receipt));
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("statusId")).thenReturn("PURCH_SHIP_SHIPPED");
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        when(delegator.findList(eq("ShipmentAndItem"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));

        Map<String, Object> result = ShipmentServices.updatePurchaseShipmentFromReceipt(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
    }

    @Test
    void updatePurchaseShipmentFromReceiptUpdatesStatusToShippedThenReceivedWhenQuantitiesMatch() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue receipt = mock(GenericValue.class);
        when(receipt.getBigDecimal("quantityAccepted")).thenReturn(new BigDecimal("5"));
        when(receipt.getBigDecimal("quantityRejected")).thenReturn(null);
        when(receipt.getString("productId")).thenReturn("PROD1");
        when(delegator.findList(eq("ShipmentReceipt"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(receipt));

        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("statusId")).thenReturn("PURCH_SHIP_CREATED");
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));

        GenericValue shipmentItem = mock(GenericValue.class);
        when(shipmentItem.getBigDecimal("quantity")).thenReturn(new BigDecimal("5"));
        when(shipmentItem.getString("productId")).thenReturn("PROD1");
        when(delegator.findList(eq("ShipmentAndItem"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipmentItem));

        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.runSync(eq("updateShipment"), any())).thenReturn(ServiceUtil.returnSuccess());
        DispatchContext dctx = mockDispatchContext(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));

        Map<String, Object> result = ShipmentServices.updatePurchaseShipmentFromReceipt(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        verify(dispatcher, times(2)).runSync(eq("updateShipment"), any());
    }

    @Test
    void updatePurchaseShipmentFromReceiptReturnsSuccessWhenQuantitiesDoNotMatch() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue receipt = mock(GenericValue.class);
        when(receipt.getBigDecimal("quantityAccepted")).thenReturn(new BigDecimal("3"));
        when(receipt.getBigDecimal("quantityRejected")).thenReturn(null);
        when(receipt.getString("productId")).thenReturn("PROD1");
        when(delegator.findList(eq("ShipmentReceipt"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(receipt));

        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("statusId")).thenReturn("PURCH_SHIP_SHIPPED");
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));

        GenericValue shipmentItem = mock(GenericValue.class);
        when(shipmentItem.getBigDecimal("quantity")).thenReturn(new BigDecimal("5"));
        when(shipmentItem.getString("productId")).thenReturn("PROD1");
        when(delegator.findList(eq("ShipmentAndItem"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipmentItem));

        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));

        Map<String, Object> result = ShipmentServices.updatePurchaseShipmentFromReceipt(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
    }

    @Test
    void updatePurchaseShipmentFromReceiptReturnsErrorOnEntityException() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenThrow(new GenericEntityException("boom"));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));

        Map<String, Object> result = ShipmentServices.updatePurchaseShipmentFromReceipt(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void duplicateShipmentRouteSegmentReturnsErrorWhenSegmentNotFound() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("ShipmentRouteSegment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentRouteSegmentId", "00001");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.duplicateShipmentRouteSegment(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void duplicateShipmentRouteSegmentCreatesNewSegment() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue routeSeg = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentRouteSegment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(routeSeg));
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        Map<String, Object> tmpResult = ServiceUtil.returnSuccess();
        tmpResult.put("shipmentRouteSegmentId", "00002");
        when(dispatcher.runSync(eq("createShipmentRouteSegment"), any())).thenReturn(tmpResult);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentRouteSegmentId", "00001");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.duplicateShipmentRouteSegment(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals("00002", result.get("newShipmentRouteSegmentId"));
    }

    @Test
    void duplicateShipmentRouteSegmentReturnsErrorWhenServiceCallFails() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue routeSeg = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentRouteSegment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(routeSeg));
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.runSync(eq("createShipmentRouteSegment"), any())).thenReturn(ServiceUtil.returnError("boom"));
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentRouteSegmentId", "00001");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.duplicateShipmentRouteSegment(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void duplicateShipmentRouteSegmentReturnsErrorOnServiceException() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue routeSeg = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentRouteSegment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(routeSeg));
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.runSync(eq("createShipmentRouteSegment"), any())).thenThrow(new GenericServiceException("boom"));
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentRouteSegmentId", "00001");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.duplicateShipmentRouteSegment(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void getShipmentPackageValueFromOrdersReturnsErrorWhenShipmentNotFound() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentPackageSeqId", "00001");
        context.put("currencyUomId", "USD");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.getShipmentPackageValueFromOrders(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void getShipmentPackageValueFromOrdersReturnsErrorWhenPackageNotFound() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(mock(GenericValue.class)));
        when(delegator.findList(eq("ShipmentPackage"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentPackageSeqId", "00001");
        context.put("currencyUomId", "USD");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.getShipmentPackageValueFromOrders(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void getShipmentPackageValueFromOrdersReturnsZeroWhenNoPackageContents() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(mock(GenericValue.class)));
        when(delegator.findList(eq("ShipmentPackage"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(mock(GenericValue.class)));
        when(delegator.findList(eq("PackedQtyVsOrderItemQuantity"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentPackageSeqId", "00001");
        context.put("currencyUomId", "USD");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.getShipmentPackageValueFromOrders(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals(0, ((BigDecimal) result.get("packageValue")).compareTo(BigDecimal.ZERO));
    }

    @Test
    void getShipmentPackageValueFromOrdersReturnsErrorOnEntityException() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenThrow(new GenericEntityException("boom"));
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("shipmentPackageSeqId", "00001");
        context.put("currencyUomId", "USD");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.getShipmentPackageValueFromOrders(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void createShipmentEstimateReturnsErrorWhenShipMethodLookupFails() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenThrow(new GenericEntityException("boom"));
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("productStoreShipMethId", "METH1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.createShipmentEstimate(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void createShipmentEstimateStoresNewEstimateWithoutBreaks() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue productStoreShipMeth = mock(GenericValue.class);
        when(productStoreShipMeth.getString("shipmentMethodTypeId")).thenReturn("STANDARD");
        when(productStoreShipMeth.getString("partyId")).thenReturn("PARTY1");
        when(productStoreShipMeth.getString("productStoreId")).thenReturn("STORE1");
        when(delegator.findList(eq("ProductStoreShipmentMeth"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(productStoreShipMeth));
        GenericValue estimate = mock(GenericValue.class);
        when(delegator.makeValue("ShipmentCostEstimate")).thenReturn(estimate);
        when(delegator.getNextSeqId("ShipmentCostEstimate")).thenReturn("EST1");
        when(estimate.get("shipmentCostEstimateId")).thenReturn("EST1");
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("productStoreShipMethId", "METH1");
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.createShipmentEstimate(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        assertEquals("EST1", result.get("shipmentCostEstimateId"));
        verify(delegator, times(1)).storeAll(any());
    }

    @Test
    void createShipmentEstimateReturnsErrorWhenWeightSpanRequiresBothFields() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue productStoreShipMeth = mock(GenericValue.class);
        when(delegator.findList(eq("ProductStoreShipmentMeth"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(productStoreShipMeth));
        GenericValue estimate = mock(GenericValue.class);
        when(delegator.makeValue("ShipmentCostEstimate")).thenReturn(estimate);
        when(delegator.getNextSeqId("ShipmentCostEstimate")).thenReturn("EST1");
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("productStoreShipMethId", "METH1");
        context.put("locale", Locale.US);
        context.put("wmin", new BigDecimal("1"));

        Map<String, Object> result = ShipmentServices.createShipmentEstimate(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void createShipmentEstimateReturnsErrorWhenWeightSpanInvertedAndNonZeroMax() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue productStoreShipMeth = mock(GenericValue.class);
        when(delegator.findList(eq("ProductStoreShipmentMeth"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(productStoreShipMeth));
        GenericValue estimate = mock(GenericValue.class);
        when(delegator.makeValue("ShipmentCostEstimate")).thenReturn(estimate);
        when(delegator.getNextSeqId("ShipmentCostEstimate")).thenReturn("EST1");
        DispatchContext dctx = mockDispatchContext(delegator);
        Map<String, Object> context = new HashMap<>();
        context.put("productStoreShipMethId", "METH1");
        context.put("locale", Locale.US);
        context.put("wmin", new BigDecimal("10"));
        context.put("wmax", new BigDecimal("1"));

        Map<String, Object> result = ShipmentServices.createShipmentEstimate(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void calcShipmentCostEstimateAppliesFlatFeesAndBreakQuantitiesWithNoAddressRequired() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue estimate = mock(GenericValue.class);
        // UtilNumber.getBigDecimal(Map<String,?>, ...) calls Map#get(Object), a different overload
        // from GenericEntity#get(String) -- stub through the Map view so the right method is mocked.
        Map<String, Object> estimateAsMap = estimate;
        when(estimate.getString("geoIdTo")).thenReturn(null);
        when(estimateAsMap.get("orderFlatPrice")).thenReturn(new BigDecimal("5"));
        when(estimateAsMap.get("orderItemFlatPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("orderPricePercent")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("weightUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("quantityUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("priceUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("featurePercent")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("featurePrice")).thenReturn(BigDecimal.ZERO);
        when(estimate.getString("productFeatureGroupId")).thenReturn(null);
        when(estimate.getBigDecimal("oversizeUnit")).thenReturn(null);
        when(estimateAsMap.get("shippingPricePercent")).thenReturn(BigDecimal.ZERO);
        when(estimate.getRelatedOne("WeightQuantityBreak", true)).thenReturn(null);
        when(estimate.getRelatedOne("QuantityQuantityBreak", true)).thenReturn(null);
        when(estimate.getRelatedOne("PriceQuantityBreak", true)).thenReturn(null);
        when(delegator.findList(eq("ShipmentCostEstimate"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(estimate));
        DispatchContext dctx = mockDispatchContext(delegator);

        Map<String, Object> context = new HashMap<>();
        context.put("locale", Locale.US);
        context.put("productStoreId", "STORE1");
        context.put("carrierRoleTypeId", "CARRIER");
        context.put("carrierPartyId", "PARTY1");
        context.put("shipmentMethodTypeId", "STANDARD");
        context.put("initialEstimateAmt", BigDecimal.ZERO);
        context.put("shippableTotal", BigDecimal.TEN);
        context.put("shippableQuantity", BigDecimal.ONE);
        context.put("shippableWeight", BigDecimal.ONE);

        Map<String, Object> result = ShipmentServices.calcShipmentCostEstimate(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        BigDecimal shippingEstimateAmount = (BigDecimal) result.get("shippingEstimateAmount");
        assertTrue(shippingEstimateAmount.compareTo(BigDecimal.ZERO) > 0, "flat fee should produce a positive shipping estimate");
    }

    @Test
    void calcShipmentCostEstimateExcludesEstimateWhenWeightOutsideBreakRange() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue estimate = mock(GenericValue.class);
        Map<String, Object> estimateAsMap = estimate;
        when(estimate.getString("geoIdTo")).thenReturn(null);
        when(estimateAsMap.get("orderFlatPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("orderItemFlatPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("orderPricePercent")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("weightUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("quantityUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("priceUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("featurePercent")).thenReturn(BigDecimal.ZERO);
        when(estimateAsMap.get("featurePrice")).thenReturn(BigDecimal.ZERO);
        when(estimate.getString("productFeatureGroupId")).thenReturn(null);
        when(estimate.getBigDecimal("oversizeUnit")).thenReturn(null);
        when(estimateAsMap.get("shippingPricePercent")).thenReturn(BigDecimal.ZERO);

        GenericValue weightBreak = mock(GenericValue.class);
        when(weightBreak.getBigDecimal("fromQuantity")).thenReturn(new BigDecimal("100"));
        when(weightBreak.getBigDecimal("thruQuantity")).thenReturn(new BigDecimal("200"));
        when(estimate.getRelatedOne("WeightQuantityBreak", true)).thenReturn(weightBreak);
        when(estimate.getRelatedOne("QuantityQuantityBreak", true)).thenReturn(null);
        when(estimate.getRelatedOne("PriceQuantityBreak", true)).thenReturn(null);
        when(delegator.findList(eq("ShipmentCostEstimate"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(estimate));
        DispatchContext dctx = mockDispatchContext(delegator);

        Map<String, Object> context = new HashMap<>();
        context.put("locale", Locale.US);
        context.put("productStoreId", "STORE1");
        context.put("carrierRoleTypeId", "CARRIER");
        context.put("carrierPartyId", "PARTY1");
        context.put("shipmentMethodTypeId", "STANDARD");
        context.put("initialEstimateAmt", BigDecimal.ZERO);
        context.put("shippableTotal", BigDecimal.TEN);
        context.put("shippableQuantity", BigDecimal.ONE);
        context.put("shippableWeight", BigDecimal.ONE);

        Map<String, Object> result = ShipmentServices.calcShipmentCostEstimate(dctx, context);

        assertTrue(ServiceUtil.isFailure(result));
    }

    @Test
    void calcShipmentCostEstimatePicksHighestPriorityEstimateAndAppliesFeatureAndSizeSurcharges() throws Exception {
        Delegator delegator = mockDelegator();

        GenericValue lowPriorityEstimate = mock(GenericValue.class);
        Map<String, Object> lowPriorityAsMap = lowPriorityEstimate;
        when(lowPriorityEstimate.getString("geoIdTo")).thenReturn(null);
        when(lowPriorityAsMap.get("partyId")).thenReturn(null);
        when(lowPriorityAsMap.get("roleTypeId")).thenReturn(null);
        when(lowPriorityAsMap.get("weightBreakId")).thenReturn(null);
        when(lowPriorityAsMap.get("quantityBreakId")).thenReturn(null);
        when(lowPriorityAsMap.get("priceBreakId")).thenReturn(null);
        when(lowPriorityEstimate.getRelatedOne("WeightQuantityBreak", true)).thenReturn(null);
        when(lowPriorityEstimate.getRelatedOne("QuantityQuantityBreak", true)).thenReturn(null);
        when(lowPriorityEstimate.getRelatedOne("PriceQuantityBreak", true)).thenReturn(null);

        GenericValue highPriorityEstimate = mock(GenericValue.class);
        Map<String, Object> highPriorityAsMap = highPriorityEstimate;
        when(highPriorityEstimate.getString("geoIdTo")).thenReturn(null);
        when(highPriorityAsMap.get("partyId")).thenReturn("PARTY1");
        when(highPriorityAsMap.get("roleTypeId")).thenReturn("CARRIER");
        when(highPriorityAsMap.get("weightBreakId")).thenReturn(null);
        when(highPriorityAsMap.get("quantityBreakId")).thenReturn(null);
        when(highPriorityAsMap.get("priceBreakId")).thenReturn(null);
        when(highPriorityEstimate.getRelatedOne("WeightQuantityBreak", true)).thenReturn(null);
        when(highPriorityEstimate.getRelatedOne("QuantityQuantityBreak", true)).thenReturn(null);
        when(highPriorityEstimate.getRelatedOne("PriceQuantityBreak", true)).thenReturn(null);
        when(highPriorityAsMap.get("orderFlatPrice")).thenReturn(BigDecimal.ZERO);
        when(highPriorityAsMap.get("orderItemFlatPrice")).thenReturn(BigDecimal.ZERO);
        when(highPriorityAsMap.get("orderPricePercent")).thenReturn(BigDecimal.ZERO);
        when(highPriorityAsMap.get("weightUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(highPriorityAsMap.get("quantityUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(highPriorityAsMap.get("priceUnitPrice")).thenReturn(BigDecimal.ZERO);
        when(highPriorityAsMap.get("featurePercent")).thenReturn(BigDecimal.ZERO);
        when(highPriorityAsMap.get("featurePrice")).thenReturn(new BigDecimal("2"));
        when(highPriorityEstimate.getString("productFeatureGroupId")).thenReturn("FEATGRP1");
        when(highPriorityEstimate.getBigDecimal("oversizeUnit")).thenReturn(new BigDecimal("10"));
        when(highPriorityEstimate.getBigDecimal("oversizePrice")).thenReturn(new BigDecimal("3"));
        when(highPriorityAsMap.get("shippingPricePercent")).thenReturn(BigDecimal.ZERO);

        GenericValue featureGroupAppl = mock(GenericValue.class);
        when(delegator.findList(eq("ProductFeatureGroupAppl"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(featureGroupAppl));
        when(delegator.findList(eq("ShipmentCostEstimate"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(lowPriorityEstimate, highPriorityEstimate));
        DispatchContext dctx = mockDispatchContext(delegator);

        Map<String, Object> itemInfo = new HashMap<>();
        itemInfo.put("size", new BigDecimal("15"));
        itemInfo.put("quantity", BigDecimal.ONE);
        itemInfo.put("featureSet", java.util.Set.of("FEAT1"));

        Map<String, Object> context = new HashMap<>();
        context.put("locale", Locale.US);
        context.put("productStoreId", "STORE1");
        context.put("carrierRoleTypeId", "CARRIER");
        context.put("carrierPartyId", "PARTY1");
        context.put("shipmentMethodTypeId", "STANDARD");
        context.put("initialEstimateAmt", BigDecimal.ZERO);
        context.put("shippableTotal", BigDecimal.TEN);
        context.put("shippableQuantity", BigDecimal.ONE);
        context.put("shippableWeight", BigDecimal.ONE);
        context.put("shippableItemInfo", List.of(itemInfo));

        Map<String, Object> result = ShipmentServices.calcShipmentCostEstimate(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
        BigDecimal shippingEstimateAmount = (BigDecimal) result.get("shippingEstimateAmount");
        // feature surcharge (featurePrice * quantity = 2) + size surcharge (oversizePrice = 3) = 5
        assertEquals(0, new BigDecimal("5").compareTo(shippingEstimateAmount));
    }

    @Test
    void calcShipmentCostEstimateExcludesEstimateWhenAddressRequiredButMissing() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue estimate = mock(GenericValue.class);
        when(estimate.getString("geoIdTo")).thenReturn("GEO_US");
        when(delegator.findList(eq("ShipmentCostEstimate"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(estimate));
        DispatchContext dctx = mockDispatchContext(delegator);

        Map<String, Object> context = new HashMap<>();
        context.put("locale", Locale.US);
        context.put("productStoreId", "STORE1");
        context.put("carrierRoleTypeId", "CARRIER");
        context.put("carrierPartyId", "PARTY1");
        context.put("shipmentMethodTypeId", "STANDARD");
        context.put("initialEstimateAmt", BigDecimal.ZERO);

        Map<String, Object> result = ShipmentServices.calcShipmentCostEstimate(dctx, context);

        assertTrue(ServiceUtil.isFailure(result));
    }

    @Test
    void updateShipmentsFromStagingReturnsSuccessWhenNoStagedPackages() throws Exception {
        Delegator delegator = mockDelegator();
        EntityListIterator iterator = mock(EntityListIterator.class);
        when(iterator.next()).thenReturn(null);
        when(delegator.find(eq("OdbcPackageIn"), nullable(EntityCondition.class), any(), any(), any(), any(EntityFindOptions.class)))
                .thenReturn(iterator);
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.updateShipmentsFromStaging(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
    }

    @Test
    void updateShipmentsFromStagingUpdatesShipmentAndClearsStagingForPackage() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue pkgInfo = mock(GenericValue.class);
        when(pkgInfo.getString("shipmentPackageSeqId")).thenReturn("00001");
        when(pkgInfo.getString("shipmentId")).thenReturn("SHIP1");
        when(pkgInfo.getString("voidIndicator")).thenReturn("N");

        EntityListIterator iterator = mock(EntityListIterator.class);
        when(iterator.next()).thenReturn(pkgInfo, (GenericValue) null);
        when(delegator.find(eq("OdbcPackageIn"), nullable(EntityCondition.class), any(), any(), any(), any(EntityFindOptions.class)))
                .thenReturn(iterator);

        GenericValue shipmentPackage = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentPackage"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipmentPackage));

        GenericValue rtSeg = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentRouteSegment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(rtSeg));

        GenericValue pkgRtSeg = mock(GenericValue.class);
        when(delegator.findList(eq("ShipmentPackageRouteSeg"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(pkgRtSeg));

        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.runSync(eq("updateShipment"), any())).thenReturn(ServiceUtil.returnSuccess());
        when(dispatcher.runSync(eq("clearShipmentStaging"), any())).thenReturn(ServiceUtil.returnSuccess());

        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        // GenericPK#getPrimaryKey() (invoked by a Map-based EntityQuery.where()) resolves its
        // delegator through DelegatorFactory's static registry, not through the instance passed
        // to EntityQuery.use() -- stub that registry lookup so the resolved GenericPK stays usable.
        try (MockedStatic<DelegatorFactory> delegatorFactory = Mockito.mockStatic(DelegatorFactory.class)) {
            delegatorFactory.when(() -> DelegatorFactory.getDelegator(anyString())).thenReturn(delegator);

            Map<String, Object> result = ShipmentServices.updateShipmentsFromStaging(dctx, context);

            assertTrue(ServiceUtil.isSuccess(result));
            verify(dispatcher, times(1)).runSync(eq("updateShipment"), any());
            verify(dispatcher, times(1)).runSync(eq("clearShipmentStaging"), any());
        }
    }

    @Test
    void updateShipmentsFromStagingSkipsPackageWhenShipmentPackageNotFound() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue pkgInfo = mock(GenericValue.class);
        when(pkgInfo.getString("shipmentPackageSeqId")).thenReturn("00002");
        when(pkgInfo.getString("shipmentId")).thenReturn("SHIP1");

        EntityListIterator iterator = mock(EntityListIterator.class);
        when(iterator.next()).thenReturn(pkgInfo, (GenericValue) null);
        when(delegator.find(eq("OdbcPackageIn"), nullable(EntityCondition.class), any(), any(), any(), any(EntityFindOptions.class)))
                .thenReturn(iterator);

        when(delegator.findList(eq("ShipmentPackage"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());

        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.updateShipmentsFromStaging(dctx, context);

        assertTrue(ServiceUtil.isSuccess(result));
    }

    @Test
    void updateShipmentsFromStagingReturnsErrorOnEntityException() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.find(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class)))
                .thenThrow(new GenericEntityException("boom"));
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.updateShipmentsFromStaging(dctx, context);

        assertTrue(ServiceUtil.isError(result));
    }

    @Test
    void sendShipmentCompleteNotificationReturnsFailureWhenEmailSettingMissing() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("primaryOrderId")).thenReturn("ORDER1");
        GenericValue orderHeader = mock(GenericValue.class);
        when(orderHeader.get("productStoreId")).thenReturn("STORE1");
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        when(delegator.findList(eq("OrderHeader"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(orderHeader));
        when(delegator.findList(eq("ProductStoreEmailSetting"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());

        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mockDispatchContext(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        Map<String, Object> result = ShipmentServices.sendShipmentCompleteNotification(dctx, context);

        assertTrue(ServiceUtil.isFailure(result));
    }

    @Test
    void sendShipmentCompleteNotificationReturnsErrorWhenNoEmailFound() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("primaryOrderId")).thenReturn("ORDER1");
        when(shipment.getString("partyIdTo")).thenReturn("PARTY1");
        GenericValue orderHeader = mock(GenericValue.class);
        when(orderHeader.get("productStoreId")).thenReturn("STORE1");
        GenericValue productStoreEmail = mock(GenericValue.class);
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        when(delegator.findList(eq("OrderHeader"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(orderHeader));
        when(delegator.findList(eq("ProductStoreEmailSetting"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(productStoreEmail));

        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mockDispatchContext(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        try (MockedStatic<PartyWorker> partyWorker = Mockito.mockStatic(PartyWorker.class)) {
            partyWorker.when(() -> PartyWorker.findPartyLatestContactMech("PARTY1", "EMAIL_ADDRESS", delegator)).thenReturn(null);

            Map<String, Object> result = ShipmentServices.sendShipmentCompleteNotification(dctx, context);

            assertTrue(ServiceUtil.isError(result));
        }
    }

    @Test
    void sendShipmentCompleteNotificationSendsMailWhenEmailFound() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("primaryOrderId")).thenReturn("ORDER1");
        when(shipment.getString("partyIdTo")).thenReturn("PARTY1");
        GenericValue orderHeader = mock(GenericValue.class);
        when(orderHeader.get("productStoreId")).thenReturn("STORE1");
        GenericValue productStoreEmail = mock(GenericValue.class);
        when(productStoreEmail.getString("bodyScreenLocation")).thenReturn("component://product/template/email/ShipmentCompleteEmail.ftl");
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        when(delegator.findList(eq("OrderHeader"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(orderHeader));
        when(delegator.findList(eq("ProductStoreEmailSetting"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(productStoreEmail));

        GenericValue email = mock(GenericValue.class);
        when(email.getString("infoString")).thenReturn("buyer@example.com");

        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.runSync(eq("sendMailFromScreen"), any())).thenReturn(ServiceUtil.returnSuccess());
        DispatchContext dctx = mockDispatchContext(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        try (MockedStatic<PartyWorker> partyWorker = Mockito.mockStatic(PartyWorker.class)) {
            partyWorker.when(() -> PartyWorker.findPartyLatestContactMech("PARTY1", "EMAIL_ADDRESS", delegator)).thenReturn(email);
            partyWorker.when(() -> PartyWorker.findPartyLastLocale("PARTY1", delegator)).thenReturn(Locale.US);

            Map<String, Object> result = ShipmentServices.sendShipmentCompleteNotification(dctx, context);

            assertTrue(ServiceUtil.isSuccess(result));
            verify(dispatcher, times(1)).runSync(eq("sendMailFromScreen"), any());
        }
    }

    @Test
    void sendShipmentCompleteNotificationReturnsErrorWhenSendMailFails() throws Exception {
        Delegator delegator = mockDelegator();
        GenericValue shipment = mock(GenericValue.class);
        when(shipment.getString("primaryOrderId")).thenReturn("ORDER1");
        when(shipment.getString("partyIdTo")).thenReturn("PARTY1");
        GenericValue orderHeader = mock(GenericValue.class);
        when(orderHeader.get("productStoreId")).thenReturn("STORE1");
        GenericValue productStoreEmail = mock(GenericValue.class);
        when(delegator.findList(eq("Shipment"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipment));
        when(delegator.findList(eq("OrderHeader"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(orderHeader));
        when(delegator.findList(eq("ProductStoreEmailSetting"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(productStoreEmail));

        GenericValue email = mock(GenericValue.class);
        when(email.getString("infoString")).thenReturn("buyer@example.com");

        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.runSync(eq("sendMailFromScreen"), any())).thenThrow(new GenericServiceException("boom"));
        DispatchContext dctx = mockDispatchContext(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        Map<String, Object> context = new HashMap<>();
        context.put("shipmentId", "SHIP1");
        context.put("userLogin", mock(GenericValue.class));
        context.put("locale", Locale.US);

        try (MockedStatic<PartyWorker> partyWorker = Mockito.mockStatic(PartyWorker.class)) {
            partyWorker.when(() -> PartyWorker.findPartyLatestContactMech("PARTY1", "EMAIL_ADDRESS", delegator)).thenReturn(email);
            partyWorker.when(() -> PartyWorker.findPartyLastLocale("PARTY1", delegator)).thenReturn(null);

            Map<String, Object> result = ShipmentServices.sendShipmentCompleteNotification(dctx, context);

            assertTrue(ServiceUtil.isError(result));
        }
    }
}
