package cz.cuni.lf1.lge.ThunderSTORM.drift;

import cz.cuni.lf1.lge.ThunderSTORM.estimators.OneLocationFitter;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.Molecule;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.RadialSymmetryFitter;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.ASHRendering;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.RenderingMethod;
import ij.process.FHT;
import ij.process.FloatProcessor;
import java.awt.geom.Point2D;
import java.util.Arrays;
import cz.cuni.lf1.lge.ThunderSTORM.util.MathProxy;
import cz.cuni.lf1.lge.ThunderSTORM.util.VectorMath;
import ij.IJ;
import ij.ImageStack;
import ij.process.ImageProcessor;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.interpolation.LoessInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.apache.commons.math3.util.MathArrays;

/**
 *
 */
public class CrossCorrelationDriftCorrection {

    private int imageWidth;
    private int imageHeight;
    private double magnification = 5;
    private int binCount;
    private boolean saveCorrelationImages;
    private ImageStack correlationImages;
    private double[] x;
    private double[] y;
    private double[] frame;
    private double[][] xBinnedByFrame; //xBinnedByFrame[bin]
    private double[][] yBinnedByFrame;
    private double minFrame, maxFrame;
    private double[] binDriftX;
    private double[] binDriftY;
    private double[] binCenters;
    private UnivariateFunction xFunction;
    private UnivariateFunction yFunction;

    /**
     *
     * @param x [px]
     * @param y [px]
     * @param frame
     * @param steps
     * @param renderingMagnification
     * @param roiWidth - [px] width of the original image or -1 for max(x)
     * @param roiHeight - [px] height of the original image or -1 for max(y)
     */
    public CrossCorrelationDriftCorrection(double[] x, double[] y, double[] frame, int steps, double renderingMagnification, int roiWidth, int roiHeight, boolean saveCorrelationImages) {
        this.x = x;
        this.y = y;
        this.frame = frame;
        this.binCount = steps;
        this.magnification = renderingMagnification;
        this.saveCorrelationImages = saveCorrelationImages;
        this.imageWidth = (roiWidth < 1) ? (int) MathProxy.ceil(VectorMath.max(x)) : roiWidth;
        this.imageHeight = (roiHeight < 1) ? (int) MathProxy.ceil(VectorMath.max(y)) : roiHeight;

        run();
    }

    public double[] getBinCenters() {
        return binCenters;
    }

    public double[] getBinDriftX() {
        return binDriftX;
    }

    public double[] getBinDriftY() {
        return binDriftY;
    }

    public int getMinFrame() {
        return (int) minFrame;
    }

    public int getMaxFrame() {
        return (int) maxFrame;
    }

    public double getMagnification() {
        return magnification;
    }

    public int getBinCount() {
        return binCount;
    }

    public ImageStack getCorrelationImages() {
        return correlationImages;
    }

    private void run() {
        int paddedSize = nextPowerOf2(MathProxy.max((int) (imageWidth * magnification), (int) (imageHeight * magnification)));
        int originalImageSize = MathProxy.max(imageWidth, imageHeight);
        double minRoi = 0 - ((double) paddedSize / magnification - originalImageSize) / 2;
        double maxRoi = originalImageSize + ((double) paddedSize / magnification - originalImageSize) / 2;
//    double ymin = 0 - ((double) paddedSize / magnification - imageHeight) / 2;
//    double ymax = imageHeight + ((double) paddedSize / magnification - imageHeight) / 2;

        binResultByFrame();

        if(saveCorrelationImages) {
            correlationImages = new ImageStack(paddedSize, paddedSize);
        }

        binDriftX = new double[binCount];
        binDriftY = new double[binCount];
        binDriftX[0] = 0;   //first image has no drift
        binDriftY[0] = 0;

        RenderingMethod renderer = new ASHRendering.Builder().imageSize(paddedSize, paddedSize).roi(minRoi, maxRoi, minRoi, maxRoi).shifts(2).build();
        FHT firstImage = new FHT(renderer.getRenderedImage(xBinnedByFrame[0], yBinnedByFrame[0], null, null).getProcessor());
        firstImage.setShowProgress(false);
        firstImage.transform();
        FHT secondImage;
        for(int i = 1; i < xBinnedByFrame.length; i++) {
            IJ.showProgress((double) i / (double) (binCount - 1));
            IJ.showStatus("Processing part " + i + " from " + (binCount - 1) + "...");
            secondImage = new FHT(renderer.getRenderedImage(xBinnedByFrame[i], yBinnedByFrame[i], null, null).getProcessor());
            secondImage.setShowProgress(false);
            //new ImagePlus("render " + i,renderer.getRenderedImage(xBinnedByFrame[i], yBinnedByFrame[i], null, null).getProcessor()).show();
            secondImage.transform();

            FHT crossCorrelationImage = firstImage.conjugateMultiply(secondImage);
            crossCorrelationImage.setShowProgress(false);
            crossCorrelationImage.inverseTransform();
            crossCorrelationImage.swapQuadrants();

            if(saveCorrelationImages) {
                correlationImages.addSlice("", crossCorrelationImage);
            }

//            GaussianBlur blur = new GaussianBlur();
//            blur.blurFloat(crossCorrelationImage, magnification/2, magnification/2, 0.01);

            //find maxima
            Point2D.Double maximumCoords = findMaxima(crossCorrelationImage);
            maximumCoords = findMaximaWithSubpixelPrecision(maximumCoords, 1 + 2 * (int) (5 * magnification), crossCorrelationImage);
            binDriftX[i] = (crossCorrelationImage.getWidth() / 2 - maximumCoords.x) / magnification;
            binDriftY[i] = (crossCorrelationImage.getHeight() / 2 - maximumCoords.y) / magnification;
        }

        //interpolate the drift using loess interpolator, or linear interpolation if not enough data for loess
        if(binCount < 4) {
            LinearInterpolator interpolator = new LinearInterpolator();
            xFunction = addLinearExtrapolationToBorders(interpolator.interpolate(binCenters, binDriftX));
            yFunction = addLinearExtrapolationToBorders(interpolator.interpolate(binCenters, binDriftY));
        } else {
            LoessInterpolator interpolator = new LoessInterpolator(0.5, 0);
            xFunction = addLinearExtrapolationToBorders(interpolator.interpolate(binCenters, binDriftX));
            yFunction = addLinearExtrapolationToBorders(interpolator.interpolate(binCenters, binDriftY));
        }
        x = null;
        y = null;
        frame = null;
        xBinnedByFrame = null;
        yBinnedByFrame = null;
        IJ.showStatus("");
        IJ.showProgress(1.0);
    }

    private void binResultByFrame() {
        //find min and max frame
        minFrame = frame[0];
        maxFrame = frame[0];
        for(int i = 0; i < frame.length; i++) {
            if(frame[i] < minFrame) {
                minFrame = frame[i];
            }
            if(frame[i] > maxFrame) {
                maxFrame = frame[i];
            }
        }
        if(maxFrame == minFrame) {
            throw new RuntimeException("Requires multiple frames.");
        }

        MathArrays.sortInPlace(frame, x, y);
        int detectionsPerBin = frame.length / binCount;

        //alloc space for binned results
        xBinnedByFrame = new double[binCount][];
        yBinnedByFrame = new double[binCount][];
        binCenters = new double[binCount];
        int currentPos = 0;
        for(int i = 0; i < binCount; i++) {
            int endPos = currentPos + detectionsPerBin;
            if(endPos >= frame.length || i == binCount-1) {
                endPos = frame.length;
            } else {
                double frameAtEndPos = frame[endPos-1];
                while(endPos < frame.length - 1 && frame[endPos] == frameAtEndPos) {
                    endPos++;
                }
            }
            if(currentPos > frame.length - 1) {
                xBinnedByFrame[i] = new double[0];
                yBinnedByFrame[i] = new double[0];
                binCenters[i] = maxFrame;
            } else {
                xBinnedByFrame[i] = Arrays.copyOfRange(x, currentPos, endPos);
                yBinnedByFrame[i] = Arrays.copyOfRange(y, currentPos, endPos);
                binCenters[i] = (frame[currentPos] + frame[endPos-1]) / 2;
            }
            currentPos = endPos;
        }
    }

    private static int nextPowerOf2(int num) {
        int powof2 = 1;
        while(powof2 < num) {
            powof2 <<= 1;
        }
        return powof2;
    }

    private static Point2D.Double findMaxima(FloatProcessor crossCorrelationImage) {
        float[] pixels = (float[]) crossCorrelationImage.getPixels();
        int maxIndex = 0;
        float max = pixels[0];
        for(int i = 0; i < pixels.length; i++) {
            if(pixels[i] > max) {
                max = pixels[i];
                maxIndex = i;
            }
        }
        return new Point2D.Double(maxIndex % crossCorrelationImage.getWidth(), maxIndex / crossCorrelationImage.getWidth());
    }

    private Point2D.Double findMaximaWithSubpixelPrecision(Point2D.Double maximumCoords, int roiSize, FHT crossCorrelationImage) {
        double[] subImageData = new double[roiSize * roiSize];
        float[] pixels = (float[]) crossCorrelationImage.getPixels();
        int roiX = (int) maximumCoords.x - (roiSize - 1) / 2;
        int roiY = (int) maximumCoords.y - (roiSize - 1) / 2;

        if(isCloseToBorder((int) maximumCoords.x, (int) maximumCoords.y, (roiSize - 1) / 2, crossCorrelationImage)) {
            return maximumCoords;
        }

        for(int ys = roiY; ys < roiY + roiSize; ys++) {
            int offset1 = (ys - roiY) * roiSize;
            int offset2 = ys * crossCorrelationImage.getWidth() + roiX;
            for(int xs = 0; xs < roiSize; xs++) {
                subImageData[offset1++] = pixels[offset2++];
            }
        }

        OneLocationFitter.SubImage subImage = new OneLocationFitter.SubImage(roiSize, null, null, subImageData, 0, 0);
        RadialSymmetryFitter radialSymmetryFitter = new RadialSymmetryFitter();
        Molecule psf = radialSymmetryFitter.fit(subImage);

        return new Point2D.Double((int) maximumCoords.x + psf.getX(), (int) maximumCoords.y + psf.getY());
    }

    public Point2D.Double getInterpolatedDrift(double frameNumber) {
        return new Point2D.Double(xFunction.value(frameNumber), yFunction.value(frameNumber));
    }

    //
    private PolynomialSplineFunction addLinearExtrapolationToBorders(PolynomialSplineFunction spline) {
        PolynomialFunction[] polynomials = spline.getPolynomials();
        double[] knots = spline.getKnots();

        boolean addToBeginning = knots[0] != minFrame;
        boolean addToEnd = knots[knots.length - 1] != maxFrame;
        int sizeIncrease = 0 + (addToBeginning ? 1 : 0) + (addToEnd ? 1 : 0);
        if(!addToBeginning && !addToEnd) {
            return spline; //do nothing
        }

        //construct new knots and polynomial arrays
        double[] newKnots = new double[knots.length + sizeIncrease];
        PolynomialFunction[] newPolynomials = new PolynomialFunction[polynomials.length + sizeIncrease];
        //add to beginning
        if(addToBeginning) {
            //add knot
            newKnots[0] = minFrame;
            System.arraycopy(knots, 0, newKnots, 1, knots.length);
            //add function
            double derivativeAtFirstKnot = polynomials[0].derivative().value(0);
            double valueAtFirstKnot = spline.value(knots[0]);
            PolynomialFunction beginningFunction = new PolynomialFunction(new double[]{valueAtFirstKnot - (knots[0] - minFrame) * derivativeAtFirstKnot, derivativeAtFirstKnot});
            newPolynomials[0] = beginningFunction;
            System.arraycopy(polynomials, 0, newPolynomials, 1, polynomials.length);
        } else {
            System.arraycopy(knots, 0, newKnots, 0, knots.length);
            System.arraycopy(polynomials, 0, newPolynomials, 0, polynomials.length);
        }
        //add to end
        if(addToEnd) {
            //add knot
            newKnots[newKnots.length - 1] = maxFrame;
            //add function
            double derivativeAtLastKnot = polynomials[polynomials.length - 1].polynomialDerivative().value(knots[knots.length - 1] - knots[knots.length - 2]);
            double valueAtLastKnot = spline.value(knots[knots.length - 1]);
            PolynomialFunction endFunction = new PolynomialFunction(new double[]{valueAtLastKnot, derivativeAtLastKnot});
            newPolynomials[newPolynomials.length - 1] = endFunction;
        }

        return new PolynomialSplineFunction(newKnots, newPolynomials);

    }

    boolean isCloseToBorder(int x, int y, int subimageSize, ImageProcessor image) {
        if(x < subimageSize || x > image.getWidth() - subimageSize - 1) {
            return true;
        }
        if(y < subimageSize || y > image.getHeight() - subimageSize - 1) {
            return true;
        }
        return false;
    }
}
