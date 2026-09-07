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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityFindOptions;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceUtil;
import org.junit.jupiter.api.Test;

class ShipmentWorkerTest {

    private static Delegator mockDelegator() {
        Delegator delegator = mock(Delegator.class);
        when(delegator.getDelegator()).thenReturn(delegator);
        return delegator;
    }

    private static DispatchContext mockDispatchContext(LocalDispatcher dispatcher) {
        DispatchContext dctx = mock(DispatchContext.class);
        Delegator delegator = mockDelegator();
        try {
            when(delegator.findList(eq("SystemProperty"), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                    .thenReturn(List.of());
        } catch (GenericEntityException e) {
            throw new IllegalStateException(e);
        }
        when(dctx.getDelegator()).thenReturn(delegator);
        when(dctx.getDispatcher()).thenReturn(dispatcher);
        return dctx;
    }

    @Test
    void getShipmentPackageContentValueAveragesIssuedOrderItemValue() throws GenericEntityException {
        GenericValue shipmentItem = mock(GenericValue.class);
        GenericValue issuance = mock(GenericValue.class);
        when(issuance.getBigDecimal("quantity")).thenReturn(new BigDecimal("2"));
        when(issuance.getBigDecimal("cancelQuantity")).thenReturn(null);
        GenericValue orderItem = mock(GenericValue.class);
        when(orderItem.getBigDecimal("selectedAmount")).thenReturn(BigDecimal.ONE);
        when(orderItem.getBigDecimal("unitPrice")).thenReturn(new BigDecimal("10"));
        when(issuance.getRelatedOne("OrderItem", false)).thenReturn(orderItem);
        when(shipmentItem.getRelated("ItemIssuance", null, null, false)).thenReturn(List.of(issuance));

        GenericValue shipmentPackageContent = mock(GenericValue.class);
        when(shipmentPackageContent.getBigDecimal("quantity")).thenReturn(new BigDecimal("2"));
        when(shipmentPackageContent.getRelatedOne("ShipmentItem", false)).thenReturn(shipmentItem);

        BigDecimal value = ShipmentWorker.getShipmentPackageContentValue(shipmentPackageContent);

        assertEquals(new BigDecimal("20.0000000000"), value);
    }

    @Test
    void getShipmentPackageContentValueWithNoIssuancesThrowsOnDivisionByZero() throws GenericEntityException {
        // Pre-existing behavior: totalIssued stays BigDecimal.ZERO when there are no issuances,
        // and the method divides by it unconditionally. Documenting the current (buggy) behavior
        // rather than changing it, since fixing it is out of scope for this move.
        GenericValue shipmentItem = mock(GenericValue.class);
        when(shipmentItem.getRelated("ItemIssuance", null, null, false)).thenReturn(List.of());

        GenericValue shipmentPackageContent = mock(GenericValue.class);
        when(shipmentPackageContent.getBigDecimal("quantity")).thenReturn(new BigDecimal("3"));
        when(shipmentPackageContent.getRelatedOne("ShipmentItem", false)).thenReturn(shipmentItem);

        assertThrows(ArithmeticException.class, () -> ShipmentWorker.getShipmentPackageContentValue(shipmentPackageContent));
    }

    @Test
    void getProductItemInfoFindsMatchingProduct() {
        Map<String, Object> item1 = UtilMisc.toMap("productId", "PROD1", "weight", new BigDecimal("1"));
        Map<String, Object> item2 = UtilMisc.toMap("productId", "PROD2", "weight", new BigDecimal("2"));

        Map<String, Object> found = ShipmentWorker.getProductItemInfo(List.of(item1, item2), "PROD2");

        assertEquals("PROD2", found.get("productId"));
    }

    @Test
    void getProductItemInfoReturnsNullWhenNoMatch() {
        Map<String, Object> item1 = UtilMisc.toMap("productId", "PROD1");

        Map<String, Object> found = ShipmentWorker.getProductItemInfo(List.of(item1), "PROD-MISSING");

        assertNull(found);
    }

    @Test
    void getProductItemInfoWithEmptyListReturnsNull() {
        assertNull(ShipmentWorker.getProductItemInfo(List.of(), "PROD1"));
    }

    @Test
    void getPackageSplitWithEmptyItemInfoReturnsEmptyList() {
        DispatchContext dctx = mockDispatchContext(mock(LocalDispatcher.class));

        List<Map<String, BigDecimal>> packages = ShipmentWorker.getPackageSplit(dctx, List.of(), new BigDecimal("50"));

        assertEquals(0, packages.size());
    }

    @Test
    void getPackageSplitBuildsSinglePackageWhenUnderMaxWeight() {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mockDispatchContext(dispatcher);

        Map<String, Object> itemInfo = new HashMap<>();
        itemInfo.put("productId", "PROD1");
        itemInfo.put("piecesIncluded", 1L);
        itemInfo.put("quantity", new BigDecimal("1"));
        itemInfo.put("weight", new BigDecimal("5"));
        itemInfo.put("productWeight", new BigDecimal("5"));
        itemInfo.put("weightUomId", "WT_lb");

        List<Map<String, BigDecimal>> packages = ShipmentWorker.getPackageSplit(dctx, List.of(itemInfo), new BigDecimal("50"));

        assertEquals(1, packages.size());
        assertEquals(BigDecimal.ONE, packages.get(0).get("PROD1"));
    }

    @Test
    void calcPackageWeightSumsWeightsInPoundsWithoutConversion() {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        DispatchContext dctx = mockDispatchContext(dispatcher);

        Map<String, Object> itemInfo = new HashMap<>();
        itemInfo.put("productId", "PROD1");
        itemInfo.put("productWeight", new BigDecimal("4"));
        itemInfo.put("weightUomId", "WT_lb");

        Map<String, BigDecimal> packageMap = new HashMap<>();
        packageMap.put("PROD1", new BigDecimal("2"));

        BigDecimal weight = ShipmentWorker.calcPackageWeight(dctx, packageMap, List.of(itemInfo), BigDecimal.ONE);

        assertEquals(new BigDecimal("9"), weight);
    }

    @Test
    void calcPackageWeightConvertsNonPoundUnitsBeforeSumming() throws Exception {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        Map<String, Object> conversionResult = new HashMap<>();
        conversionResult.put(ModelService.RESPONSE_MESSAGE, ModelService.RESPOND_SUCCESS);
        conversionResult.put("convertedValue", new BigDecimal("8.8"));
        when(dispatcher.runSync(eq("convertUom"), any(Map.class))).thenReturn(conversionResult);
        DispatchContext dctx = mockDispatchContext(dispatcher);

        Map<String, Object> itemInfo = new HashMap<>();
        itemInfo.put("productId", "PROD1");
        itemInfo.put("productWeight", new BigDecimal("4"));
        itemInfo.put("weightUomId", "WT_kg");

        Map<String, BigDecimal> packageMap = new HashMap<>();
        packageMap.put("PROD1", BigDecimal.ONE);

        BigDecimal weight = ShipmentWorker.calcPackageWeight(dctx, packageMap, List.of(itemInfo), BigDecimal.ZERO);

        assertEquals(new BigDecimal("8.8"), weight);
    }

    @Test
    void calcPackageWeightReturnsAccumulatedWeightWhenConversionServiceErrors() throws Exception {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.runSync(eq("convertUom"), any(Map.class))).thenReturn(ServiceUtil.returnError("boom"));
        DispatchContext dctx = mockDispatchContext(dispatcher);

        Map<String, Object> itemInfo = new HashMap<>();
        itemInfo.put("productId", "PROD1");
        itemInfo.put("productWeight", new BigDecimal("4"));
        itemInfo.put("weightUomId", "WT_kg");

        Map<String, BigDecimal> packageMap = new HashMap<>();
        packageMap.put("PROD1", BigDecimal.ONE);

        BigDecimal weight = ShipmentWorker.calcPackageWeight(dctx, packageMap, List.of(itemInfo), BigDecimal.ZERO);

        assertEquals(BigDecimal.ZERO, weight);
    }
}
