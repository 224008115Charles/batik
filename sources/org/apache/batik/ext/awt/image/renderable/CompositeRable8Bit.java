/*****************************************************************************
 * Copyright (C) The Apache Software Foundation. All rights reserved.        *
 * ------------------------------------------------------------------------- *
 * This software is published under the terms of the Apache Software License *
 * version 1.1, a copy of which has been included with this distribution in  *
 * the LICENSE file.                                                         *
 *****************************************************************************/

package org.apache.batik.ext.awt.image.renderable;

import org.apache.batik.ext.awt.image.GraphicsUtil;

import java.util.List;
import java.util.Vector;
import java.util.Iterator;

import java.awt.color.ColorSpace;
import java.awt.Composite;
import java.awt.Shape;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import java.awt.geom.Rectangle2D;
import java.awt.geom.AffineTransform;

import java.awt.image.RenderedImage;

import java.awt.image.renderable.RenderContext;

import org.apache.batik.ext.awt.image.rendered.CachableRed;
import org.apache.batik.ext.awt.image.rendered.CompositeRed;
import org.apache.batik.ext.awt.image.rendered.FloodRed;

/**
 * Composites a list of images according to a single composite rule.
 * the image are applied in the order they are in the List given.
 *
 * @author <a href="mailto:Thomas.DeWeeese@Kodak.com">Thomas DeWeese</a>
 * @version $Id$
 */
public class CompositeRable8Bit
    extends    AbstractRable
    implements CompositeRable, PaintRable {

    protected CompositeRule rule;
    protected ColorSpace    colorspace;
    protected boolean       csIsLinear;

    public CompositeRable8Bit(List srcs,
                              CompositeRule rule,
                              boolean csIsLinear) {
        super(srcs);

        this.rule = rule;
        this.csIsLinear = csIsLinear;
        if (csIsLinear)
            colorspace = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
        else
            colorspace = ColorSpace.getInstance(ColorSpace.CS_sRGB);
    }

      /**
       * The sources to be composited togeather.
       * @param srcs The list of images to be composited by the composite rule.
       */
    public void setSources(List srcs) {
        init(srcs, null);
    }

      /**
       * Set the composite rule to use for combining the sources.
       * @param cr Composite rule to use.
       */
    public void setCompositeRule(CompositeRule cr) {
        touch();
        this.rule = rule;
    }

      /**
       * Get the composite rule in use for combining the sources.
       * @return Composite rule currently in use.
       */
    public CompositeRule getCompositeRule() {
        return this.rule;
    }

      /**
       * Set the colorspace to perform compositing in
       * @param cs ColorSpace to use.
       */
    public void setCompositeColorSpace(ColorSpace cs) {
        touch();
        if (cs == ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB))
            csIsLinear = true;
        else if (cs == ColorSpace.getInstance(ColorSpace.CS_sRGB))
            csIsLinear = false;
        else
            throw new IllegalArgumentException
                ("Unsupported ColorSpace for Composite: " + cs);
        this.colorspace = cs;
    }

      /**
       * Get the colorspace to that compositing will be performed in
       * @return ColorSpace for compositing.
       */
    public ColorSpace getCompositeColorSpace() {
        return this.colorspace;
    }

    /**
     * Should perform the equivilent action as 
     * createRendering followed by drawing the RenderedImage to 
     * Graphics2D, or return false.
     *
     * @param g2d The Graphics2D to draw to.
     * @return true if the paint call succeeded, false if
     *         for some reason the paint failed (in which 
     *         case a createRendering should be used).
     */
    public boolean paintRable(Graphics2D g2d) {
        // This optimization only apply if we are using
        // SrcOver.  Otherwise things break...
        Composite c = g2d.getComposite();
        if (!SVGComposite.OVER.equals(c))
            return false;
        
        // For the over mode we can just draw them in order...
        if (getCompositeRule() != CompositeRule.OVER) 
            return false;

        ColorSpace crCS = getCompositeColorSpace();
        ColorSpace g2dCS = GraphicsUtil.getDestinationColorSpace(g2d);
        if ((g2dCS == null) || (g2dCS != crCS))
            return false;

        // System.out.println("drawImage : " + g2dCS +
        //                    crCS);
        Iterator i = getSources().iterator();
        while (i.hasNext()) {
            GraphicsUtil.drawImage(g2d, (Filter)i.next());
        }
        return true;
    }

    public RenderedImage createRendering(RenderContext rc) {
        if (srcs.size() == 0)
            return null;

        // Just copy over the rendering hints.
        RenderingHints rh = rc.getRenderingHints();
        if (rh == null) rh = new RenderingHints(null);

        // update the current affine transform
        AffineTransform at = rc.getTransform();

        Rectangle2D aoi = rc.getAreaOfInterest().getBounds2D();
        if (aoi != null) {
            Rectangle2D bounds2d = getBounds2D();
            if (bounds2d.intersects(aoi) == false)
                return null;
                
            Rectangle2D.intersect(aoi, bounds2d, aoi);
        }

        Rectangle devRect = at.createTransformedShape(aoi).getBounds();

        rc = new RenderContext(at, aoi, rh);

        Vector srcs = new Vector();
        
        Iterator i = getSources().iterator();
        while (i.hasNext()) {
            // Get the source to work with...
            Filter filt = (Filter)i.next();

            // Get our sources image...
            RenderedImage ri = filt.createRendering(rc);
            if (ri != null) {
                CachableRed cr;
                cr = GraphicsUtil.wrap(ri);

                if (csIsLinear)
                    cr = GraphicsUtil.convertToLsRGB(cr);
                else
                    cr = GraphicsUtil.convertTosRGB(cr);

                srcs.add(cr);
                
            } else {
                
                // Blank image...
                switch (rule.getRule()) {
                case CompositeRule.RULE_IN:
                    // For Mode IN One blank image kills all output
                    // (including any "future" images to be drawn).
                    return null;

                case CompositeRule.RULE_OUT:
                    // For mode OUT blank image clears output 
                    // up to this point, so ignore inputs to this point.
                    srcs.clear();
                    break;

                case CompositeRule.RULE_ARITHMETIC:
                    srcs.add(new FloodRed(devRect));
                    break;

                default:
                    // All other cases we simple pretend the image didn't
                    // exist (fully transparent image has no affect).
                    break;
                }
            }
        }

        if (srcs.size() == 0)
            return null;

        // System.out.println("Done General: " + rule);
        return new CompositeRed(srcs, rule);
    }
}
