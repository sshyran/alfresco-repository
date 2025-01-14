/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2005 - 2016 Alfresco Software Limited
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

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;

import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.TransformationOptions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import static org.alfresco.repo.rendition2.RenditionDefinition2.SOURCE_ENCODING;

/**
 * Converts any textual format to plain text.
 * <p>
 * The transformation is sensitive to the source and target string encodings.
 *
 * @author Derek Hulley
 *
 * @deprecated The transformations code is being moved out of the codebase and replaced by the new async RenditionService2 or other external libraries.
 */
@Deprecated
public class StringExtractingContentTransformer extends AbstractRemoteContentTransformer
{
    public static final String PREFIX_TEXT = "text/";

    private static final Log logger = LogFactory.getLog(StringExtractingContentTransformer.class);

    /**
     * Gives a high reliability for all translations from <i>text/sometype</i> to
     * <i>text/plain</i>.  As the text formats are already text, the characters
     * are preserved and no actual conversion takes place.
     * <p>
     * Extraction of text from binary data is wholly unreliable.
     */
    @Override
    public boolean isTransformableMimetype(String sourceMimetype, String targetMimetype, TransformationOptions options)
    {
        if (!targetMimetype.equals(MimetypeMap.MIMETYPE_TEXT_PLAIN))
        {
            // can only convert to plain text
            return false;
        }
        else if (sourceMimetype.equals(MimetypeMap.MIMETYPE_TEXT_PLAIN) ||
                sourceMimetype.equals(MimetypeMap.MIMETYPE_JAVASCRIPT))
        {
            // conversions from any plain text format are very reliable
            return true;
        }
        else if (sourceMimetype.startsWith(PREFIX_TEXT) ||
                sourceMimetype.equals(MimetypeMap.MIMETYPE_DITA))
        {
            // the source is text, but probably with some kind of markup
            return true;
        }
        else
        {
            // extracting text from binary is not useful
            return false;
        }
    }

    @Override
    public String getComments(boolean available)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(super.getComments(available));
        sb.append("# Only supports transformation of js, dita and mimetypes starting with \"");
        sb.append(PREFIX_TEXT);
        sb.append("\" to txt.\n");
        return sb.toString();
    }

    @Override
    protected Log getLogger()
    {
        return logger;
    }

    /**
     * Text to text conversions are done directly using the content reader and writer string
     * manipulation methods.
     * <p>
     * Extraction of text from binary content attempts to take the possible character
     * encoding into account.  The text produced from this will, if the encoding was correct,
     * be unformatted but valid.
     */
    @Override
    public void transformLocal(ContentReader reader, ContentWriter writer,  TransformationOptions options)
            throws Exception
    {
        // is this a straight text-text transformation
        transformText(reader, writer, options);
    }

    /**
     * Transformation optimized for text-to-text conversion
     */
    private void transformText(ContentReader reader, ContentWriter writer, TransformationOptions options) throws Exception
    {
        // get a char reader and writer
        Reader charReader = null;
        Writer charWriter = null;
        try
        {
            if (reader.getEncoding() == null)
            {
                charReader = new InputStreamReader(reader.getContentInputStream());
            }
            else
            {
                charReader = new InputStreamReader(reader.getContentInputStream(), reader.getEncoding());
            }
            if (writer.getEncoding() == null)
            {
                charWriter = new OutputStreamWriter(writer.getContentOutputStream());
            }
            else
            {
                charWriter = new OutputStreamWriter(writer.getContentOutputStream(), writer.getEncoding());
            }
            // copy from the one to the other
            char[] buffer = new char[8192];
            int readCount = 0;
            while (readCount > -1)
            {
                // write the last read count number of bytes
                charWriter.write(buffer, 0, readCount);
                // fill the buffer again
                readCount = charReader.read(buffer);
            }
        }
        finally
        {
            if (charReader != null)
            {
                try { charReader.close(); } catch (Throwable e) { logger.error(e); }
            }
            if (charWriter != null)
            {
                try { charWriter.close(); } catch (Throwable e) { logger.error(e); }
            }
        }
        // done
    }

    @Override
    protected void transformRemote(RemoteTransformerClient remoteTransformerClient, ContentReader reader,
                                   ContentWriter writer, TransformationOptions options, String sourceMimetype,
                                   String targetMimetype, String sourceExtension, String targetExtension,
                                   String targetEncoding) throws Exception
    {
        String sourceEncoding = reader.getEncoding();
        long timeoutMs = options.getTimeoutMs();

        remoteTransformerClient.request(reader, writer, sourceMimetype, sourceExtension, targetExtension,
                timeoutMs, logger,
                "sourceMimetype", sourceMimetype,
                "targetMimetype", targetMimetype,
                SOURCE_ENCODING, sourceEncoding,
                "targetEncoding", targetEncoding);

    }
}
