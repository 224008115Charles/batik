/*****************************************************************************
 * Copyright (C) The Apache Software Foundation. All rights reserved.        *
 * ------------------------------------------------------------------------- *
 * This software is published under the terms of the Apache Software License *
 * version 1.1, a copy of which has been included with this distribution in  *
 * the LICENSE file.                                                         *
 *****************************************************************************/

package org.apache.batik.ext.awt.image.rendered;

import org.apache.batik.ext.awt.image.renderable.PadMode;
import org.apache.batik.ext.awt.image.renderable.CompositeRule;
import org.apache.batik.ext.awt.image.renderable.SVGComposite;
import org.apache.batik.ext.awt.image.GraphicsUtil;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.CompositeContext;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import java.awt.color.ColorSpace;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.awt.image.SampleModel;
import java.awt.image.ColorModel;
import java.awt.image.BufferedImage;
import java.awt.image.AffineTransformOp;

import java.util.Iterator;
import java.util.List;
import java.util.Vector;

/**
 * This is an implementation of an affine operation as a RenderedImage.
 * Right now the implementation makes use of the AffineBufferedImageOp
 * to do the work.  Eventually this may move to be more tiled in nature.
 *
 * @author <a href="mailto:Thomas.DeWeeese@Kodak.com">Thomas DeWeese</a>
 * @version $Id$ */
public class CompositeRed extends AbstractRed {

    CompositeRule rule;
    CompositeContext [] contexts;

    public CompositeRed(List srcs, CompositeRule rule) {
        super(); // We _must_ call init...

        CachableRed src = (CachableRed)srcs.get(0);

        ColorModel  cm = fixColorModel (src);

        this.rule = rule;

        SVGComposite comp = new SVGComposite(rule);
        contexts = new CompositeContext[srcs.size()];

        int idx = 0;
        Iterator i = srcs.iterator();
        Rectangle myBounds = null;
        while (i.hasNext()) {
            CachableRed cr = (CachableRed)i.next();

            contexts[idx++] = comp.createContext(cr.getColorModel(), cm, null);

            Rectangle newBound = cr.getBounds();
            if (myBounds == null) {
                myBounds = newBound;
                continue;
            }

            switch (rule.getRule()) {
            case CompositeRule.RULE_IN:
                if (myBounds.intersects(newBound))
                    myBounds = myBounds.intersection(newBound);
                else {
                    myBounds.width = 0;
                    myBounds.height = 0;
                }
                break;
            case CompositeRule.RULE_OUT:
                // Last node determines bounds...
                myBounds = newBound;
                break;
            default:
                myBounds= myBounds.union(newBound);
            }
        }

        if (myBounds == null) 
            throw new IllegalArgumentException
                ("Composite Operation Must have some source!");

        if (rule.getRule() == CompositeRule.RULE_ARITHMETIC) {
            Vector vec = new Vector();
            i = srcs.iterator();
            while (i.hasNext()) {
                CachableRed cr = (CachableRed)i.next();
                Rectangle r = cr.getBounds();
                // For arithmatic make sure they are all the same size...
                if ((r.x      != myBounds.x) ||
                    (r.y      != myBounds.y) ||
                    (r.width  != myBounds.width) ||
                    (r.height != myBounds.height))
                    cr = new PadRed(cr, myBounds, PadMode.ZERO_PAD, null);
                vec.add(cr);
            }
            srcs = vec;
        }

        // fix my sample model so it makes sense given my size.
        SampleModel sm = fixSampleModel(src, myBounds);

        // System.out.println("Comp: " + myBounds);
        // System.out.println("  SM: " + sm.getWidth()+"x"+sm.getHeight());

        // Make our tile grid fall on the closes multiply of 256.
        int tgX = myBounds.x & 0xFFFFFF00;
        int tgY = myBounds.y & 0xFFFFFF00; 

        // Finish initializing our base class...
        init(srcs, myBounds, cm, sm, tgX, tgY, null);
    }

    public WritableRaster copyData(WritableRaster wr) {
        // copyToRaster(wr);
        genRect(wr);
        return wr;
    }

    public Raster getTile(int x, int y) {
        int tx = tileGridXOff+x*tileWidth;
        int ty = tileGridYOff+y*tileHeight;
        Point pt = new Point(tx, ty);
        WritableRaster wr = Raster.createWritableRaster(sm, pt);
        genRect(wr);
        
        return wr;
    }

    public void emptyRect(WritableRaster wr) {
        PadRed.ZeroRecter zr = PadRed.ZeroRecter.getZeroRecter(wr);
        zr.zeroRect(new Rectangle(wr.getMinX(), wr.getMinY(), 
                                  wr.getWidth(), wr.getHeight()));
    }

    public void genRect(WritableRaster wr) {
        // long startTime = System.currentTimeMillis();
        // System.out.println("Comp GenR: " + wr);
        Rectangle r = wr.getBounds();
        
        int idx = 0;
        Iterator i = srcs.iterator();
        boolean first = true;
        while (i.hasNext()) {
            CachableRed cr = (CachableRed)i.next();
            if (first) {
                Rectangle crR = cr.getBounds();
                if ((r.x < crR.x)                   || 
                    (r.y < crR.y)                   ||
                    (r.x+r.width > crR.x+crR.width) ||
                    (r.y+r.height > crR.y+crR.height))
                    // Portions outside my bounds, zero them...
                    emptyRect(wr);

                // Fill in initial image...
                cr.copyData(wr);

                if (cr.getColorModel().isAlphaPremultiplied() == false)
                    GraphicsUtil.coerceData(wr, cr.getColorModel(), true);
                first = false;
            } else {
                Rectangle crR = cr.getBounds();
                if (crR.intersects(r)) {
                    Rectangle smR = crR.intersection(r);
                    Raster ras = cr.getData(smR);
                    WritableRaster smWR = wr.createWritableChild
                        (smR.x, smR.y, smR.width, smR.height, 
                         smR.x, smR.y, null);
                    
                    contexts[idx].compose(ras, smWR, smWR);
                }
            }

            idx++;
        }
        // long endTime = System.currentTimeMillis();
        // System.out.println("Other: " + (endTime-startTime));
    }

    // This is an alternate Implementation that uses drawImage.
    // In testing this was not significantly faster and it had some
    // problems with alpha premultiplied.
    public void genRect_OVER(WritableRaster wr) {
        // long startTime = System.currentTimeMillis();
        // System.out.println("Comp GenR: " + wr);
        Rectangle r = wr.getBounds();

        ColorModel cm = getColorModel();

        BufferedImage bi = new BufferedImage
            (cm, wr.createWritableTranslatedChild(0,0), 
             cm.isAlphaPremultiplied(), null);

        Graphics2D g2d = GraphicsUtil.createGraphics(bi);
        g2d.translate(-r.x, -r.y);

        Iterator i = srcs.iterator();
        boolean first = true;
        while (i.hasNext()) {
            CachableRed cr = (CachableRed)i.next();
            if (first) {
                Rectangle crR = cr.getBounds();
                if ((r.x < crR.x)                   || 
                    (r.y < crR.y)                   ||
                    (r.x+r.width > crR.x+crR.width) ||
                    (r.y+r.height > crR.y+crR.height))
                    // Portions outside my bounds, zero them...
                    emptyRect(wr);

                // Fill in initial image...
                cr.copyData(wr);

                GraphicsUtil.coerceData(wr, cr.getColorModel(), 
                                        cm.isAlphaPremultiplied());
                first = false;
            } else {
                GraphicsUtil.drawImage(g2d, cr);
            }
        }
        // long endTime = System.currentTimeMillis();
        // System.out.println("OVER: " + (endTime-startTime));
    }

        /**
         * This function 'fixes' the source's sample model.
         * right now it just ensures that the sample model isn't
         * much larger than my width.
         */
    protected static SampleModel fixSampleModel(CachableRed src,
                                                Rectangle   bounds) {
        SampleModel sm = src.getSampleModel();
        int tgX = bounds.x & 0xFFFFFF00;
        int tw  = (bounds.x+bounds.width)-tgX;
        int  w  = sm.getWidth();
        if (w < 256) w = 256;
        if (w > tw)  w = tw;

        int tgY = bounds.y & 0xFFFFFF00;
        int th  = (bounds.y+bounds.height)-tgY;
        int h   = sm.getHeight();
        if (h < 256) h = 256;
        if (h > th)  h = th;

        // System.out.println("tg: " + tgX + "x" + tgY);
        // System.out.println("t: " + tw + "x" + th);
        // System.out.println("sz: " + w + "x" + h);

        return sm.createCompatibleSampleModel(w, h);
    }

    protected static ColorModel fixColorModel(CachableRed src) {
        ColorModel  cm = src.getColorModel();

        if (cm.hasAlpha()) {
            if (!cm.isAlphaPremultiplied())
                cm = GraphicsUtil.coerceColorModel(cm, true);
            return cm;
        }

        int b = src.getSampleModel().getNumBands()+1;
        if (b > 4)
            throw new IllegalArgumentException
                ("CompositeRed can only handle up to three band images");
        
        int [] masks = new int[4];
        for (int i=0; i < b-1; i++) 
            masks[i] = 0xFF0000 >> (8*i);
        masks[3] = 0xFF << (8*(b-1));
        ColorSpace cs = cm.getColorSpace();

        return new DirectColorModel(cs, 8*b, masks[0], masks[1], 
                                    masks[2], masks[3],
                                    true, DataBuffer.TYPE_INT);
    }
}
