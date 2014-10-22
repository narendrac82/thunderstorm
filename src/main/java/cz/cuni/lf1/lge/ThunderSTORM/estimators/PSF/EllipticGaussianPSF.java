package cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF;

import cz.cuni.lf1.lge.ThunderSTORM.calibration.CylindricalLensCalibration;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.OneLocationFitter;
import ij.IJ;
import org.apache.commons.math3.analysis.MultivariateMatrixFunction;
import static cz.cuni.lf1.lge.ThunderSTORM.util.MathProxy.*;
import static java.lang.Math.abs;
import java.util.Arrays;

/**
 * Representation of 2D elliptic Gaussian PSFModel model.
 */
public class EllipticGaussianPSF extends PSFModel {

    CylindricalLensCalibration calibration = null;
    double defaultSigma = 1.6;
    double fi, sinfi, cosfi;

    public EllipticGaussianPSF(double defaultSigma, double angle) {
        this.defaultSigma = defaultSigma;
        this.fi = angle;
        this.sinfi = Math.sin(fi);
        this.cosfi = Math.cos(fi);
    }

    public EllipticGaussianPSF(CylindricalLensCalibration calibration) {
        this.calibration = calibration;
        this.fi = calibration.getAngle();
        this.sinfi = Math.sin(fi);
        this.cosfi = Math.cos(fi);
    }
    
    @Override
    public double getValue(double[] params, double x, double y) {
        double dx = ((x - params[Params.X]) * cosfi - (y - params[Params.Y]) * sinfi);
        double dy = ((x - params[Params.X]) * sinfi + (y - params[Params.Y]) * cosfi);

        if (calibration != null) {
            params[Params.SIGMA1] = calibration.getSigma1(params[Params.Z]);
            params[Params.SIGMA2] = calibration.getSigma2(params[Params.Z]);
        }

        return params[Params.INTENSITY] / (2 * PI * params[Params.SIGMA1] * params[Params.SIGMA2]) * exp(-0.5 * (sqr(dx / params[Params.SIGMA1]) + sqr(dy / params[Params.SIGMA2]))) + params[Params.OFFSET];
    }

    @Override
    public double[] transformParameters(double[] parameters) {
        double [] transformed = Arrays.copyOf(parameters, parameters.length);
        transformed[Params.X] = parameters[Params.X];
        transformed[Params.Y] = parameters[Params.Y];
        transformed[Params.Z] = parameters[Params.Z];
        if (calibration != null) {
            transformed[Params.SIGMA1] = calibration.getSigma1(parameters[Params.Z]);
            transformed[Params.SIGMA2] = calibration.getSigma2(parameters[Params.Z]);
        } else {
            transformed[Params.SIGMA1] = parameters[Params.SIGMA1] * parameters[Params.SIGMA1];
            transformed[Params.SIGMA2] = parameters[Params.SIGMA2] * parameters[Params.SIGMA2];
        }
        transformed[Params.INTENSITY] = parameters[Params.INTENSITY] * parameters[Params.INTENSITY];
        transformed[Params.OFFSET] = parameters[Params.OFFSET] * parameters[Params.OFFSET];
        return transformed;
    }

    @Override
    public double[] transformParametersInverse(double[] parameters) {
        double [] transformed = Arrays.copyOf(parameters, parameters.length);
        transformed[Params.X] = parameters[Params.X];
        transformed[Params.Y] = parameters[Params.Y];
        transformed[Params.Z] = parameters[Params.Z];
        if (calibration != null) {
            transformed[Params.SIGMA1] = calibration.getSigma1(parameters[Params.Z]);
            transformed[Params.SIGMA2] = calibration.getSigma2(parameters[Params.Z]);
        } else {
            transformed[Params.SIGMA1] = sqrt(abs(parameters[Params.SIGMA1]));
            transformed[Params.SIGMA2] = sqrt(abs(parameters[Params.SIGMA2]));
        }
        transformed[Params.INTENSITY] = sqrt(abs(parameters[Params.INTENSITY]));
        transformed[Params.OFFSET] = sqrt(abs(parameters[Params.OFFSET]));
        return transformed;
    }

    @Override
    public MultivariateMatrixFunction getJacobianFunction(final int[] xgrid, final int[] ygrid) {
        return new MultivariateMatrixFunction() {
            @Override
            //derivations by wolfram alpha:
            //d(b^2 + ((J*J)/(2*PI*(s1*s1)*(s2*s2))) * e^( -( (((x0-x)*cos(f)-(y0-y)*sin(f))^2)/(2*s1*s1*s1*s1) + ((((x0-x)*sin(f)+(y0-y)*cos(f))^2)/(2*s2*s2*s2*s2)))))/dJ
            public double[][] value(double[] point) throws IllegalArgumentException {
                // WLSQ results from numeric Jacobian are better than the analytic solution...is there some mistake in
                // any of the formulae? or is it just given by nature of the data? speed of processing is comparable for
                // both of them...
                /**/
                double[][] retVal2 = EllipticGaussianPSF.super.getJacobianFunction(xgrid, ygrid).value(point);
                for (int i = 0; i < xgrid.length; i++) {
                    for (int j = 0; j < retVal2[i].length; j++) {
                        if (j == Params.X) continue;
                        if (j == Params.Y) continue;
                        if (j == Params.INTENSITY) continue;
                        if (j == Params.OFFSET) continue;
                        if (calibration != null) {
                            if (j == Params.Z) continue;
                        } else {
                            if (j == Params.SIGMA1) continue;
                            if (j == Params.SIGMA2) continue;
                        }
                        retVal2[i][j] = 0.0;
                    }
                }
                return retVal2;
                /**/
                /*
                double[] transformedPoint = transformParameters(point);
                double sigma1Squared = transformedPoint[Params.SIGMA1] * transformedPoint[Params.SIGMA1];
                double sigma2Squared = transformedPoint[Params.SIGMA2] * transformedPoint[Params.SIGMA2];
                double[][] retVal = new double[xgrid.length][transformedPoint.length];

                for (int i = 0; i < xgrid.length; i++) {
                    double xd = (xgrid[i] - transformedPoint[Params.X]);
                    double yd = (ygrid[i] - transformedPoint[Params.Y]);
                    double cosfiXd = cosfi * xd, cosfiYd = cosfi * yd;
                    double sinfiYd = sinfi * yd, sinfiXd = sinfi * xd;
                    double first = cosfiXd - sinfiYd, second = sinfiXd + cosfiYd;
                    double expVal = exp(-0.5 * (sqr(first) / sigma1Squared + sqr(second) / sigma2Squared));
                    double oneDivPISS2 = 1 / (PI * transformedPoint[Params.SIGMA1] * transformedPoint[Params.SIGMA2]);
                    // diff(psf, x0)
                    double pom1 = first * cosfi / sigma1Squared + second * sinfi / sigma2Squared;
                    retVal[i][Params.X] = oneDivPISS2 * 0.5 * transformedPoint[Params.INTENSITY] * pom1 * expVal;
                    // diff(psf, y0)
                    double pom2 = first * sinfi / sigma1Squared + second * cosfi / sigma2Squared;
                    retVal[i][Params.Y] = oneDivPISS2 * 0.5 * transformedPoint[Params.INTENSITY] * pom2 * expVal;
                    // diff(psf, I)
                    retVal[i][Params.INTENSITY] = point[Params.INTENSITY] * expVal * oneDivPISS2;
                    if (calibration != null) {
                        // diff(psf, z0)
                        double pom4 = calibration.dwx(transformedPoint[PSFModel.Params.Z]) / transformedPoint[Params.SIGMA1];
                        double pom5 = calibration.dwy(transformedPoint[PSFModel.Params.Z]) / transformedPoint[Params.SIGMA2];
                        double pom3 = sqr(first) / sigma1Squared * pom4 + sqr(second)/sigma2Squared * pom5;
                        retVal[i][Params.Z] = oneDivPISS2 * 0.5 * transformedPoint[Params.INTENSITY] * expVal * pom3
                                            - oneDivPISS2 * 0.5 * transformedPoint[Params.INTENSITY] * expVal * pom4
                                            - oneDivPISS2 * 0.5 * transformedPoint[Params.INTENSITY] * expVal * pom5;
                    } else {
                        // diff(psf, sigma1)
                        retVal[i][Params.SIGMA1] = transformedPoint[Params.INTENSITY] * expVal * oneDivPISS2 / point[Params.SIGMA1] * (-1 + sqr(first) / sigma1Squared);
                        // diff(psf, sigma2)
                        retVal[i][Params.SIGMA2] = transformedPoint[Params.INTENSITY] * expVal * oneDivPISS2 / point[Params.SIGMA2] * (-1 + sqr(second) / sigma2Squared);
                    }
                    // diff(psf, off)
                    retVal[i][Params.OFFSET] = 2 * point[Params.OFFSET];
                }
                */
          //IJ.log("numeric jacobian: " + Arrays.deepToString(retVal2));
          //IJ.log("analytic jacobian: " + Arrays.deepToString(retVal));
                //return retVal;
            }
        };
    }

    @Override
    public double[] getInitialSimplex() {
        double[] steps = new double[Params.PARAMS_LENGTH];
        Arrays.fill(steps, 0.001);  // cannot be zero!
        steps[Params.X] = 1;
        steps[Params.Y] = 1;
        steps[Params.Z] = 100;
        steps[Params.INTENSITY] = 3000;
        steps[Params.SIGMA1] = 0.1;
        steps[Params.SIGMA2] = 0.1;
        steps[Params.OFFSET] = 10;
        return steps;
    }

    @Override
    public double[] getInitialParams(OneLocationFitter.SubImage subImage) {
        double[] guess = new double[Params.PARAMS_LENGTH];
        Arrays.fill(guess, 0);
        guess[Params.X] = subImage.detectorX;
        guess[Params.Y] = subImage.detectorY;
        guess[Params.Z] = 0;
        guess[Params.INTENSITY] = (subImage.getMax() - subImage.getMin()) * 2 * PI * defaultSigma * defaultSigma;
        if (calibration != null) {
            guess[Params.SIGMA1] = calibration.getSigma1(guess[Params.Z]);
            guess[Params.SIGMA2] = calibration.getSigma2(guess[Params.Z]);
        } else {
            guess[Params.SIGMA1] = defaultSigma;
            guess[Params.SIGMA2] = defaultSigma;
        }
        guess[Params.OFFSET] = subImage.getMin();
        return guess;
    }
    
    @Override 
    public Molecule newInstanceFromParams(double[] params, MoleculeDescriptor.Units subImageUnits) {
        Molecule mol = new Molecule(new Params(new int[] { Params.X, Params.Y, Params.Z, Params.SIGMA1, Params.SIGMA2, Params.INTENSITY, Params.OFFSET, Params.BACKGROUND }, params, true));
        MoleculeDescriptor descriptor = mol.descriptor;
        descriptor.setColumnUnits(subImageUnits, descriptor.getParamColumn(Params.LABEL_INTENSITY));
        descriptor.setColumnUnits(subImageUnits, descriptor.getParamColumn(Params.LABEL_OFFSET));
        descriptor.setColumnUnits(subImageUnits, descriptor.getParamColumn(Params.LABEL_BACKGROUND));
        return mol;
    }

    @Override
    public double getDoF() {
        return 5;
    }
}