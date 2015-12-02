/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2015. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.net.http.handlers;

import com.nfsdb.collections.ByteSequence;
import com.nfsdb.collections.DirectByteCharSequence;
import com.nfsdb.exceptions.DisconnectedChannelException;
import com.nfsdb.factory.JournalFactory;
import com.nfsdb.io.parser.CsvParser;
import com.nfsdb.io.parser.FormatParser;
import com.nfsdb.io.parser.PipeParser;
import com.nfsdb.io.parser.TabParser;
import com.nfsdb.io.parser.listener.JournalImportListener;
import com.nfsdb.io.parser.listener.MetadataExtractorListener;
import com.nfsdb.misc.Chars;
import com.nfsdb.net.IOContext;
import com.nfsdb.net.http.RequestHeaderBuffer;

import java.io.IOException;

public class ImportHandler extends AbstractMultipartHandler {

    private final JournalFactory factory;

    public ImportHandler(JournalFactory factory) {
        this.factory = factory;
    }

    @Override
    public void park(IOContext context) {

    }

    private void analyseColumns(IOContext context, long address, int len) {
        // analyse columns and their types
        int sampleSize = 100;
        MetadataExtractorListener lsnr = (MetadataExtractorListener) context.threadContext.getCache().get(IOWorkerContextKey.ME.name());
        if (lsnr == null) {
            lsnr = new MetadataExtractorListener();
            context.threadContext.getCache().put(IOWorkerContextKey.ME.name(), lsnr);
        }

        context.textParser.parse(address, len, sampleSize, lsnr);
        lsnr.onLineCount(context.textParser.getLineCount());
        context.importer.onMetadata(lsnr.getMetadata());
        context.textParser.setHeader(lsnr.isHeader());
        context.textParser.restart();
        context.analysed = true;
    }

    private void analyseFormat(IOContext context, long address, int len) {
        FormatParser parser = new FormatParser().of(address, len);
        context.dataFormatValid = parser.getFormat() != null && parser.getStdDev() < 0.5;

        if (context.dataFormatValid) {
            if (context.textParser != null) {
                context.textParser.clear();
            } else {
                switch (parser.getFormat()) {
                    case CSV:
                        context.textParser = new CsvParser();
                        break;
                    case TAB:
                        context.textParser = new TabParser();
                        break;
                    case PIPE:
                        context.textParser = new PipeParser();
                        break;
                    default:
                        context.dataFormatValid = false;
                }
            }
        }
    }

    @Override
    protected void onComplete0(IOContext context) throws IOException {
        context.response.status(200, "text/html; charset=utf-8");
        context.response.flushHeader();
        context.response.put("OK, imported\r\n");
        context.response.end();
    }

    @Override
    protected void onData(IOContext context, RequestHeaderBuffer hb, ByteSequence data) {
        if (hb.getContentDispositionFilename() != null) {
            long lo = ((DirectByteCharSequence) data).getLo();
            int len = data.length();
            if (!context.analysed) {
                analyseFormat(context, lo, len);
                if (context.dataFormatValid) {
                    analyseColumns(context, lo, len);
                }
            }

            if (context.dataFormatValid) {
                context.textParser.parse(lo, len, Integer.MAX_VALUE, context.importer);
            }
        }
    }

    @Override
    protected void onPartBegin(IOContext context, RequestHeaderBuffer hb) throws IOException {
        if (hb.getContentDispositionFilename() != null) {
            if (Chars.equals(hb.getContentDispositionName(), "data")) {
                context.analysed = false;
                context.importer = new JournalImportListener(factory, hb.getContentDispositionFilename().toString());
            } else {
                context.response.simple(400, "Unrecognised field");
                throw DisconnectedChannelException.INSTANCE;
            }
        }
    }

    @Override
    protected void onPartEnd(IOContext context) throws IOException {
        if (context.textParser != null) {
            context.textParser.parseLast();
            context.textParser.close();
            context.textParser = null;
            context.importer.close();
        }
    }
}
