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
package org.ofbiz.content.output;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.DocAttribute;
import javax.print.attribute.DocAttributeSet;
import javax.print.attribute.HashDocAttributeSet;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.HashPrintServiceAttributeSet;
import javax.print.attribute.PrintRequestAttribute;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.PrintServiceAttributeSet;
import javax.print.attribute.standard.PrinterName;
import javax.xml.transform.stream.StreamSource;

import org.apache.fop.apps.Fop;
import org.apache.fop.apps.MimeConstants;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.collections.MapStack;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.webapp.view.ApacheFopWorker;
import org.ofbiz.widget.renderer.WidgetRenderOptions;
import org.ofbiz.widget.renderer.ScreenRenderer;
import org.ofbiz.widget.renderer.ScreenStringRenderer;
import org.ofbiz.widget.renderer.macro.MacroScreenRenderer;


/**
 * Output Services
 * <p>
 * SCIPIO: WARN: These services currently do not support a form widget renderer,
 * so they can only be used to render screens implemented with freemarker *.fo.ftl files.
 * <p>
 * SCIPIO: TODO: Re-add support for form widget renderer once possible.
 * This service originally supported xsl-fo form widgets in distant past, but FoFormRenderer was long obsolete, and
 * MacroFormRenderer currently requires HttpServletRequest/Response, which is not available from these services (NOTE: and we could NOT use caller's request!).
 * When MacroFormRenderer finally supports running from non-webapp context, it should be added here.
 * For now, the most common screen used with these is OrderPrintScreens.xml#OrderPDF, which in Scipio
 * should contain no form widgets. See {@link #sendPrintFromScreen} and {@link #createFileFromScreen}.
 */
public class OutputServices {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    public static final String resource = "ContentUiLabels";

    public static Map<String, Object> sendPrintFromScreen(DispatchContext dctx, Map<String, ? extends Object> serviceContext) {
        Locale locale = (Locale) serviceContext.get("locale");
        String screenLocation = (String) serviceContext.remove("screenLocation");
        Map<String, Object> screenContext = UtilGenerics.checkMap(serviceContext.remove("screenContext"));
        String contentType = (String) serviceContext.remove("contentType");
        String printerContentType = (String) serviceContext.remove("printerContentType");

        if (UtilValidate.isEmpty(screenContext)) {
            screenContext = new HashMap<String, Object>();
        }
        screenContext.put("locale", locale);
        if (UtilValidate.isEmpty(contentType)) {
            contentType = "application/postscript";
        }
        if (UtilValidate.isEmpty(printerContentType)) {
            printerContentType = contentType;
        }

        try {

            MapStack<String> screenContextTmp = MapStack.create();
            screenContextTmp.put("locale", locale);

            Writer writer = new StringWriter();
            // substitute the freemarker variables...
            ScreenStringRenderer foScreenStringRenderer = new MacroScreenRenderer(EntityUtilProperties.getPropertyValue("widget", "screenfop.name", dctx.getDelegator()),
                            EntityUtilProperties.getPropertyValue("widget", "screenfop.screenrenderer", dctx.getDelegator()));

            // SCIPIO: TODO: form widget renderer support
            //FormStringRenderer foFormRenderer = new MacroFormRenderer(EntityUtilProperties.getPropertyValue("widget", "screenfop.name", dctx.getDelegator()),
            //        EntityUtilProperties.getPropertyValue("widget", "screenfop.formrenderer", dctx.getDelegator()), request, response);

            ScreenRenderer screensAtt = ScreenRenderer.makeWithEnvAwareFetching(writer, screenContextTmp, foScreenStringRenderer);
            screensAtt.populateContextForService(dctx, screenContext);
            screenContextTmp.putAll(screenContext);
            // SCIPIO: TODO: form widget renderer support
            //screensAtt.getContext().put("formStringRenderer", foFormRenderer);
            WidgetRenderOptions.setInContext(screensAtt.getContext(), WidgetRenderOptions.create(dctx, locale).setWarnMissingFormRenderer(true)); // SCIPIO: for logging; see ModelScreenWidget
            screensAtt.render(screenLocation);

            // create the input stream for the generation
            StreamSource src = new StreamSource(new StringReader(writer.toString()));

            // create the output stream for the generation
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            Fop fop = ApacheFopWorker.createFopInstance(baos, MimeConstants.MIME_PDF);
            ApacheFopWorker.transform(src, null, fop);

            baos.flush();
            baos.close();

            // Print is sent
            DocFlavor psInFormat = new DocFlavor.INPUT_STREAM(printerContentType);
            InputStream bais = new ByteArrayInputStream(baos.toByteArray());

            DocAttributeSet docAttributeSet = new HashDocAttributeSet();
            List<Object> docAttributes = UtilGenerics.checkList(serviceContext.remove("docAttributes"));
            if (UtilValidate.isNotEmpty(docAttributes)) {
                for (Object da : docAttributes) {
                    Debug.logInfo("Adding DocAttribute: " + da, module);
                    docAttributeSet.add((DocAttribute) da);
                }
            }

            Doc myDoc = new SimpleDoc(bais, psInFormat, docAttributeSet);

            PrintService printer = null;

            // lookup the print service for the supplied printer name
            String printerName = (String) serviceContext.remove("printerName");
            if (UtilValidate.isNotEmpty(printerName)) {

                PrintServiceAttributeSet printServiceAttributes = new HashPrintServiceAttributeSet();
                printServiceAttributes.add(new PrinterName(printerName, locale));

                PrintService[] printServices = PrintServiceLookup.lookupPrintServices(null, printServiceAttributes);
                if (printServices.length > 0) {
                    printer = printServices[0];
                    Debug.logInfo("Using printer: " + printer.getName(), module);
                    if (!printer.isDocFlavorSupported(psInFormat)) {
                        return ServiceUtil.returnError(UtilProperties.getMessage(resource, "ContentPrinterNotSupportDocFlavorFormat", UtilMisc.toMap("psInFormat", psInFormat, "printerName", printer.getName()), locale));
                    }
                }
                if (printer == null) {
                    return ServiceUtil.returnError(UtilProperties.getMessage(resource, "ContentPrinterNotFound", UtilMisc.toMap("printerName", printerName), locale));
                }

            } else {

                // if no printer name was supplied, try to get the default printer
                printer = PrintServiceLookup.lookupDefaultPrintService();
                if (printer != null) {
                    Debug.logInfo("No printer name supplied, using default printer: " + printer.getName(), module);
                }
            }

            if (printer == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(resource, "ContentPrinterNotAvailable", locale));
            }


            PrintRequestAttributeSet praset = new HashPrintRequestAttributeSet();
            List<Object> printRequestAttributes = UtilGenerics.checkList(serviceContext.remove("printRequestAttributes"));
            if (UtilValidate.isNotEmpty(printRequestAttributes)) {
                for (Object pra : printRequestAttributes) {
                    Debug.logInfo("Adding PrintRequestAttribute: " + pra, module);
                    praset.add((PrintRequestAttribute) pra);
                }
            }
            DocPrintJob job = printer.createPrintJob();
            job.print(myDoc, praset);
        } catch (Exception e) {
            Debug.logError(e, "Error rendering [" + contentType + "]: " + e.toString(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "ContentRenderingError", UtilMisc.toMap("contentType", contentType, "errorString", e.toString()), locale));
        }

        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> createFileFromScreen(DispatchContext dctx, Map<String, ? extends Object> serviceContext) {
        Locale locale = (Locale) serviceContext.get("locale");
        Delegator delegator = dctx.getDelegator();
        String screenLocation = (String) serviceContext.remove("screenLocation");
        Map<String, Object> screenContext = UtilGenerics.checkMap(serviceContext.remove("screenContext"));
        String contentType = (String) serviceContext.remove("contentType");
        String filePath = (String) serviceContext.remove("filePath");
        String fileName = (String) serviceContext.remove("fileName");

        if (UtilValidate.isEmpty(screenContext)) {
            screenContext = new HashMap<String, Object>();
        }
        screenContext.put("locale", locale);
        if (UtilValidate.isEmpty(contentType)) {
            contentType = "application/pdf";
        }

        try {
            MapStack<String> screenContextTmp = MapStack.create();
            screenContextTmp.put("locale", locale);

            Writer writer = new StringWriter();
            // substitute the freemarker variables...
            ScreenStringRenderer foScreenStringRenderer = new MacroScreenRenderer(EntityUtilProperties.getPropertyValue("widget", "screenfop.name", dctx.getDelegator()),
                    EntityUtilProperties.getPropertyValue("widget", "screenfop.screenrenderer", dctx.getDelegator()));

            // SCIPIO: TODO: form widget renderer support
            //FormStringRenderer foFormRenderer = new MacroFormRenderer(EntityUtilProperties.getPropertyValue("widget", "screenfop.name", dctx.getDelegator()),
            //        EntityUtilProperties.getPropertyValue("widget", "screenfop.formrenderer", dctx.getDelegator()), request, response);

            ScreenRenderer screensAtt = ScreenRenderer.makeWithEnvAwareFetching(writer, screenContextTmp, foScreenStringRenderer);
            screensAtt.populateContextForService(dctx, screenContext);
            screenContextTmp.putAll(screenContext);
            // SCIPIO: TODO: form widget renderer support
            //screensAtt.getContext().put("formStringRenderer", foFormRenderer);
            WidgetRenderOptions.setInContext(screensAtt.getContext(), WidgetRenderOptions.create(dctx, locale).setWarnMissingFormRenderer(true)); // SCIPIO: for logging; see ModelScreenWidget
            screensAtt.render(screenLocation);

            // create the input stream for the generation
            StreamSource src = new StreamSource(new StringReader(writer.toString()));

            // create the output stream for the generation
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            Fop fop = ApacheFopWorker.createFopInstance(baos, MimeConstants.MIME_PDF);
            ApacheFopWorker.transform(src, null, fop);

            baos.flush();
            baos.close();

            fileName += UtilDateTime.nowAsString();
            if ("application/pdf".equals(contentType)) {
                fileName += ".pdf";
            } else if ("application/postscript".equals(contentType)) {
                fileName += ".ps";
            } else if ("text/plain".equals(contentType)) {
                fileName += ".txt";
            }
            if (UtilValidate.isEmpty(filePath)) {
                filePath = EntityUtilProperties.getPropertyValue("content", "content.output.path", "/output", delegator);
            }
            File file = new File(filePath, fileName);

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(baos.toByteArray());
            fos.close();

        } catch (Exception e) {
            Debug.logError(e, "Error rendering [" + contentType + "]: " + e.toString(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "ContentRenderingError", UtilMisc.toMap("contentType", contentType, "errorString", e.toString()), locale));
        }

        return ServiceUtil.returnSuccess();
    }

}
