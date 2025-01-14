/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2019 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.repo.content.transform;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Map;
import java.util.Set;

/**
 * Abstract supper class for local transformer using flat transform options.
 */
public abstract class AbstractLocalTransform implements LocalTransform
{
    protected static final Log log = LogFactory.getLog(LocalTransform.class);

    protected final String name;
    protected final MimetypeService mimetypeService;
    protected final TransformerDebug transformerDebug;

    private final LocalTransformServiceRegistry localTransformServiceRegistry;
    private final boolean strictMimeTypeCheck;
    private final Map<String, Set<String>> strictMimetypeExceptions;
    private final boolean retryTransformOnDifferentMimeType;
    private final static ThreadLocal<Integer> depth = ThreadLocal.withInitial(()->0);

    AbstractLocalTransform(String name, TransformerDebug transformerDebug,
                           MimetypeService mimetypeService, boolean strictMimeTypeCheck,
                           Map<String, Set<String>> strictMimetypeExceptions, boolean retryTransformOnDifferentMimeType,
                           LocalTransformServiceRegistry localTransformServiceRegistry)
    {
        this.name = name;
        this.transformerDebug = transformerDebug;
        this.mimetypeService = mimetypeService;
        this.strictMimeTypeCheck = strictMimeTypeCheck;
        this.strictMimetypeExceptions = strictMimetypeExceptions;
        this.retryTransformOnDifferentMimeType = retryTransformOnDifferentMimeType;
        this.localTransformServiceRegistry = localTransformServiceRegistry;
    }

    public abstract boolean isAvailable();

    protected abstract void transformImpl(ContentReader reader,
                                          ContentWriter writer, Map<String, String> transformOptions,
                                          String sourceMimetype, String targetMimetype,
                                          String sourceExtension, String targetExtension,
                                          String renditionName, NodeRef sourceNodeRef)
                                          throws Exception;

    @Override
    public void transform(ContentReader reader, ContentWriter writer, Map<String, String> transformOptions,
                          String renditionName, NodeRef sourceNodeRef)
            throws Exception
    {
        if (isAvailable())
        {
            String sourceMimetype = reader.getMimetype();
            String targetMimetype = writer.getMimetype();

            String sourceExtension = mimetypeService.getExtension(sourceMimetype);
            String targetExtension = mimetypeService.getExtension(targetMimetype);
            if (sourceExtension == null || targetExtension == null)
            {
                throw new AlfrescoRuntimeException("Unknown extensions for mimetypes: \n" +
                        "   source mimetype: " + sourceMimetype + "\n" +
                        "   source extension: " + sourceExtension + "\n" +
                        "   target mimetype: " + targetMimetype + "\n" +
                        "   target extension: " + targetExtension);
            }

            transformWithDebug(reader, writer, transformOptions, renditionName, sourceNodeRef, sourceMimetype,
                    targetMimetype, sourceExtension, targetExtension);

            if (log.isDebugEnabled())
            {
                log.debug("Local transformation completed: \n" +
                        "   source: " + reader + "\n" +
                        "   target: " + writer + "\n" +
                        "   options: " + transformOptions);
            }
        }
        else
        {
            log.debug("Local transformer not available: \n" +
                    "   source: " + reader + "\n" +
                    "   target: " + writer + "\n" +
                    "   options: " + transformOptions);
        }
    }

    private void transformWithDebug(ContentReader reader, ContentWriter writer, Map<String, String> transformOptions,
                                    String renditionName, NodeRef sourceNodeRef, String sourceMimetype, String targetMimetype,
                                    String sourceExtension, String targetExtension) throws Exception
    {

        try
        {
            depth.set(depth.get()+1);

            if (transformerDebug.isEnabled())
            {
                transformerDebug.pushTransform(name, reader.getContentUrl(), sourceMimetype,
                        targetMimetype, reader.getSize(), renditionName, sourceNodeRef);
            }

            strictMimetypeCheck(reader, sourceNodeRef, sourceMimetype);
            transformImpl(reader, writer, transformOptions, sourceMimetype,
                    targetMimetype, sourceExtension, targetExtension, renditionName, sourceNodeRef);
        }
        catch (Throwable e)
        {
            retryWithDifferentMimetype(reader, writer, targetMimetype, transformOptions, renditionName, sourceNodeRef, e);
        }
        finally
        {
            transformerDebug.popTransform();
            depth.set(depth.get()-1);
        }
    }

    private void strictMimetypeCheck(ContentReader reader, NodeRef sourceNodeRef, String declaredMimetype)
            throws UnsupportedTransformationException
    {
        if (mimetypeService != null && strictMimeTypeCheck && depth.get() == 1)
        {
            String detectedMimetype = mimetypeService.getMimetypeIfNotMatches(reader.getReader());

            if (!strictMimetypeCheck(declaredMimetype, detectedMimetype))
            {
                Set<String> allowedMimetypes = strictMimetypeExceptions.get(declaredMimetype);
                if (allowedMimetypes != null && allowedMimetypes.contains(detectedMimetype))
                {
                    String fileName = transformerDebug.getFileName(sourceNodeRef, true, 0);
                    String readerSourceMimetype = reader.getMimetype();
                    String message = "Transformation of ("+fileName+
                            ") has not taken place because the declared mimetype ("+
                            readerSourceMimetype+") does not match the detected mimetype ("+
                            detectedMimetype+").";
                    log.warn(message);
                    throw new UnsupportedTransformationException(message);
                }
            }
        }
    }

    /**
     * When strict mimetype checking is performed before a transformation, this method is called.
     * There are a few issues with the Tika mimetype detection. As a result we still allow some
     * transformations to take place even if there is a discrepancy between the detected and
     * declared mimetypes.
     * @param declaredMimetype the mimetype on the source node
     * @param detectedMimetype returned by Tika having looked at the content.
     * @return true if the transformation should take place. This includes the case where the
     *         detectedMimetype is null (returned by Tika when the mimetypes are the same), or
     *         the supplied pair of mimetypes have been added to the
     *         {@code}transformer.strict.mimetype.check.whitelist{@code}.
     */
    private boolean strictMimetypeCheck(String declaredMimetype, String detectedMimetype)
    {
        if (detectedMimetype == null)
        {
            return true;
        }

        Set<String> detectedMimetypes = strictMimetypeExceptions.get(declaredMimetype);
        return detectedMimetypes != null && detectedMimetypes.contains(detectedMimetype);
    }

    private void retryWithDifferentMimetype(ContentReader reader, ContentWriter writer, String targetMimetype,
                                            Map<String, String> transformOptions, String renditionName,
                                            NodeRef sourceNodeRef, Throwable e) throws Exception
    {
        if (mimetypeService != null && localTransformServiceRegistry != null)
        {
            String differentType = mimetypeService.getMimetypeIfNotMatches(reader.getReader());
            if (differentType == null)
            {
                transformerDebug.debug("          Failed", e);
                throw new ContentIOException("Content conversion failed: \n" +
                        "   reader: " + reader + "\n" +
                        "   writer: " + writer + "\n" +
                        "   options: " + transformOptions,
                        e);
            }
            else
            {
                transformerDebug.debug("          Failed: Mime type was '" + differentType + "'", e);
                String claimedMimetype = reader.getMimetype();

                if (retryTransformOnDifferentMimeType)
                {
                    reader = reader.getReader();
                    reader.setMimetype(differentType);
                    long sourceSizeInBytes = reader.getSize();

                    LocalTransform localTransform = localTransformServiceRegistry.getLocalTransform(
                            transformOptions, renditionName, differentType, targetMimetype, sourceSizeInBytes);
                    if (localTransform == null)
                    {
                        transformerDebug.debug("          Failed", e);
                        throw new ContentIOException("Content conversion failed: \n" +
                                "   reader: " + reader + "\n" +
                                "   writer: " + writer + "\n" +
                                "   options: " + transformOptions + "\n" +
                                "   claimed mime type: " + claimedMimetype + "\n" +
                                "   detected mime type: " + differentType + "\n" +
                                "   transformer not found" + "\n",
                                e
                        );
                    }
                    localTransform.transform(reader, writer, transformOptions, renditionName, sourceNodeRef);
                }
                else
                {
                    throw new ContentIOException("Content conversion failed: \n" +
                            "   reader: " + reader + "\n" +
                            "   writer: " + writer + "\n" +
                            "   options: " + transformOptions + "\n" +
                            "   claimed mime type: " + claimedMimetype + "\n" +
                            "   detected mime type: " + differentType,
                            e
                    );
                }
            }
        }
    }
}
