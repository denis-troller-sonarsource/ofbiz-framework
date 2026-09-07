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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityFindOptions;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.junit.jupiter.api.Test;

class ShipmentEventsTest {

    private static Delegator mockDelegator() {
        Delegator delegator = mock(Delegator.class);
        when(delegator.getDelegator()).thenReturn(delegator);
        return delegator;
    }

    private static HttpServletRequest mockRequest(Delegator delegator) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("delegator")).thenReturn(delegator);
        return request;
    }

    @Test
    void viewShipmentPackageRouteSegLabelImageReturnsErrorWhenRecordNotFound() throws Exception {
        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of());

        HttpServletRequest request = mockRequest(delegator);
        when(request.getParameter("shipmentId")).thenReturn("SHIP1");
        when(request.getParameter("shipmentRouteSegmentId")).thenReturn("1");
        when(request.getParameter("shipmentPackageSeqId")).thenReturn("00001");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String result = ShipmentEvents.viewShipmentPackageRouteSegLabelImage(request, response);

        assertEquals("error", result);
    }

    @Test
    void viewShipmentPackageRouteSegLabelImageReturnsErrorWhenLabelImageMissing() throws Exception {
        GenericValue shipmentPackageRouteSeg = mock(GenericValue.class);
        when(shipmentPackageRouteSeg.getBytes("labelImage")).thenReturn(null);

        Delegator delegator = mockDelegator();
        when(delegator.findList(anyString(), any(), any(), any(), any(), any(EntityFindOptions.class), anyBoolean()))
                .thenReturn(List.of(shipmentPackageRouteSeg));

        HttpServletRequest request = mockRequest(delegator);
        when(request.getParameter("shipmentId")).thenReturn("SHIP1");
        when(request.getParameter("shipmentRouteSegmentId")).thenReturn("1");
        when(request.getParameter("shipmentPackageSeqId")).thenReturn("00001");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String result = ShipmentEvents.viewShipmentPackageRouteSegLabelImage(request, response);

        assertEquals("error", result);
    }

    @Test
    void checkForceShipmentReceivedSkipsUpdateWhenNotForced() {
        HttpSession session = mock(HttpSession.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession()).thenReturn(session);
        when(request.getParameter("shipmentIdReceived")).thenReturn("SHIP1");
        when(request.getParameter("forceShipmentReceived")).thenReturn("N");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String result = ShipmentEvents.checkForceShipmentReceived(request, response);

        assertEquals("success", result);
    }

    @Test
    void checkForceShipmentReceivedUpdatesShipmentWhenForced() throws Exception {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        Map<String, Object> successResult = ServiceUtil.returnSuccess();
        when(dispatcher.runSync(anyString(), any())).thenReturn(successResult);

        HttpSession session = mock(HttpSession.class);
        GenericValue userLogin = mock(GenericValue.class);
        when(session.getAttribute("userLogin")).thenReturn(userLogin);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("dispatcher")).thenReturn(dispatcher);
        when(request.getSession()).thenReturn(session);
        when(request.getParameter("shipmentIdReceived")).thenReturn("SHIP1");
        when(request.getParameter("forceShipmentReceived")).thenReturn("Y");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String result = ShipmentEvents.checkForceShipmentReceived(request, response);

        assertEquals("success", result);
    }

    @Test
    void checkForceShipmentReceivedReturnsErrorWhenServiceFails() throws Exception {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        Map<String, Object> errorResult = ServiceUtil.returnError("boom");
        when(dispatcher.runSync(anyString(), any())).thenReturn(errorResult);

        HttpSession session = mock(HttpSession.class);
        GenericValue userLogin = mock(GenericValue.class);
        when(session.getAttribute("userLogin")).thenReturn(userLogin);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("dispatcher")).thenReturn(dispatcher);
        when(request.getSession()).thenReturn(session);
        when(request.getParameter("shipmentIdReceived")).thenReturn("SHIP1");
        when(request.getParameter("forceShipmentReceived")).thenReturn("Y");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String result = ShipmentEvents.checkForceShipmentReceived(request, response);

        assertEquals("error", result);
    }
}
