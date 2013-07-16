/* ===========================================================
 * TradeManager : An application to trade strategies for the Java(tm) platform
 * ===========================================================
 *
 * (C) Copyright 2011-2011, by Simon Allen and Contributors.
 *
 * Project Info:  org.trade
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * [Java is a trademark or registered trademark of Oracle, Inc.
 * in the United States and other countries.]
 *
 * (C) Copyright 2011-2011, by Simon Allen and Contributors.
 *
 * Original Author:  Simon Allen;
 * Contributor(s):   -;
 *
 * Changes 
 * -------
 *
 */
package org.trade.strategy;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trade.broker.BrokerModel;
import org.trade.core.util.CoreUtils;
import org.trade.core.util.TradingCalendar;
import org.trade.core.valuetype.Money;
import org.trade.dictionary.valuetype.Action;
import org.trade.dictionary.valuetype.Side;
import org.trade.dictionary.valuetype.TradestrategyStatus;
import org.trade.persistent.dao.Entrylimit;
import org.trade.strategy.data.AverageTrueRangeDataset;
import org.trade.strategy.data.AverageTrueRangeSeries;
import org.trade.strategy.data.CandleSeries;
import org.trade.strategy.data.IndicatorSeries;
import org.trade.strategy.data.StrategyData;
import org.trade.strategy.data.atr.AverageTrueRangeItem;
import org.trade.strategy.data.candle.CandleItem;

/**
 * @author Simon Allen
 * 
 * @version $Revision: 1.0 $
 */

public class ATRStrategy extends AbstractStrategyRule {

	/**
	 * 1/ Enter in the direction of the first 5min bar with a stop at the first
	 * 5min bars open. Use a STPLMT order with a range from the Entry Limit
	 * table.
	 * 
	 * 2/ Bar must be within the Entry Limit table % Range Bar. i.e in this case
	 * 2%
	 * 
	 * 3/ If the position is not filled by 10:30 cancel the order.
	 * 
	 * 4/ Add 1c to the entry price and round over/under whole/half numbers in
	 * the direction of the trade, same for stop price.
	 * 
	 * E.g. If first 5min bar is H=21.50 L=21.15 Open= 21.2 Close=21.40 then
	 * position order will be Buy STPLMT=21.51-21.55 (STPLMT range 0.04 from
	 * EntryLimit table and as we are at 21.5 we also buy over/under whole/half
	 * numbers), Quantity=Risk/(21.51-21.2) rounded to +/-100 shares (see
	 * EntryLimit table).
	 * 
	 */

	private static final long serialVersionUID = -1373776942145894938L;
	private final static Logger _log = LoggerFactory
			.getLogger(ATRStrategy.class);

	private Side side = null;

	/**
	 * Default Constructor
	 * 
	 * @param brokerManagerModel
	 *            BrokerModel
	 * @param datasetContainer
	 *            StrategyData
	 * @param idTradestrategy
	 *            Integer
	 */

	public ATRStrategy(BrokerModel brokerManagerModel,
			StrategyData datasetContainer, Integer idTradestrategy) {
		super(brokerManagerModel, datasetContainer, idTradestrategy);
	}

	/**
	 * Method runStrategy.
	 * 
	 * @param candleSeries
	 *            CandleSeries
	 * @param newBar
	 *            boolean
	 * @see org.trade.strategy.StrategyRule#runStrategy(CandleSeries, boolean)
	 */
	public void runStrategy(CandleSeries candleSeries, boolean newBar) {

		try {
			if (getCurrentCandleCount() > 0) {
				// Get the current candle
				CandleItem currentCandleItem = this.getCurrentCandle();
				Date startPeriod = currentCandleItem.getPeriod().getStart();

				/*
				 * Trade is open kill this Strategy as its job is done.
				 */
				if (this.isThereOpenPosition()) {
					_log.info("FiveMinGapBarStrategy complete open position filled symbol: "
							+ getSymbol() + " startPeriod: " + startPeriod);
					this.cancel();
					return;
				}

				// AbstractStrategyRule.logCandle(currentCandleItem.getCandle());

				CandleItem prevCandleItem = (CandleItem) candleSeries
						.getDataItem(getCurrentCandleCount() - 1);

				AverageTrueRangeDataset datasetATR = (AverageTrueRangeDataset) getTradestrategy()
						.getDatasetContainer().getIndicatorByType(
								IndicatorSeries.AverageTrueRangeSeries);
				if (null == datasetATR) {
					error(1, 20,
							"No ATRdefined for Strategy. Please add ATR Indicator to Strategy");
					return;
				}
				AverageTrueRangeSeries seriesATR = datasetATR.getSeries(0);
				int itemCountATR = seriesATR.getItemCount();
				if (itemCountATR < 2) {
					return;
				}

				double prevATR = ((AverageTrueRangeItem) seriesATR
						.getDataItem(itemCountATR - 2)).getAverageTrueRange();

				/*
				 * Is it the the 9:35 candle? and we have not created an open
				 * position trade.
				 */
				if (startPeriod.equals(TradingCalendar.getSpecificTime(
						startPeriod, 9, 35)) && newBar) {

					/*
					 * Add the tails as a % of the body. 10% and vwap must be
					 * between O/C.
					 */
					if (CoreUtils
							.isBetween(prevCandleItem.getOpen(),
									prevCandleItem.getClose(),
									prevCandleItem.getVwap())) {
						double barBodyPercent = (Math.abs(prevCandleItem
								.getOpen() - prevCandleItem.getClose()) / Math
								.abs(prevCandleItem.getHigh()
										- prevCandleItem.getLow())) * 100;
						if (barBodyPercent < 10) {
							_log.info("Bar Body outside % range  Symbol: "
									+ getSymbol() + " Time: " + startPeriod);
							updateTradestrategyStatus(TradestrategyStatus.NBB);
							this.cancel();
							return;
						}
					}

					this.side = Side.newInstance(Side.SLD);
					if (prevCandleItem.isSide(Side.BOT)) {
						this.side = Side.newInstance(Side.BOT);
					}
					Money price = new Money(prevCandleItem.getHigh());
					Money priceStop = new Money(prevCandleItem.getOpen()
							- prevATR);
					String action = Action.BUY;
					if (this.side.equalsCode(Side.SLD)) {
						price = new Money(prevCandleItem.getLow());
						priceStop = new Money(prevCandleItem.getOpen()
								+ prevATR);
						action = Action.SELL;
					}
					Money priceClose = new Money(prevCandleItem.getClose());
					Entrylimit entrylimit = getEntryLimit()
							.getValue(priceClose);

					double highLowRange = Math.abs(prevCandleItem.getHigh()
							- prevCandleItem.getLow());

					// priceStop = new Money(prevCandleItem.getOpen());

					// If the candle less than the entry limit %
					if (((highLowRange) / prevCandleItem.getClose()) < entrylimit
							.getPercentOfPrice().doubleValue()) {

						/*
						 * Check that the entry - stop is greater than 2* the
						 * STPLMT amount.
						 */
						if (Math.abs(price.subtract(priceStop).doubleValue()) > (entrylimit
								.getLimitAmount().doubleValue() * 2)) {

							/*
							 * Create an open position.
							 */
							_log.info("We have a trade!!  Symbol: "
									+ getSymbol() + " Time: " + startPeriod);
							createRiskOpenPosition(action, price, priceStop,
									true, null, null, null, null);

						} else {
							_log.info("Rule 9:35 5min bar less than 2 * stop limits. Symbol: "
									+ getSymbol() + " Time: " + startPeriod);
							updateTradestrategyStatus(TradestrategyStatus.NBB);
							// Kill this process we are done!
							this.cancel();
						}

					} else {
						_log.info("Rule 9:35 5min bar outside % limits. Symbol: "
								+ getSymbol() + " Time: " + startPeriod);
						updateTradestrategyStatus(TradestrategyStatus.PERCENT);
						// Kill this process we are done!
						this.cancel();
					}

				} else {
					if (startPeriod.before(TradingCalendar.getSpecificTime(
							startPeriod, 10, 50))
							&& startPeriod.after(TradingCalendar
									.getSpecificTime(startPeriod, 9, 35))) {
						CandleItem firstCandle = this
								.getCandle(TradingCalendar.getSpecificTime(this
										.getTradestrategy().getTradingday()
										.getOpen(), startPeriod));

						if (Side.BOT.equals(this.side.getCode())) {
							if (currentCandleItem.getVwap() < firstCandle
									.getLow()) {
								updateTradestrategyStatus(TradestrategyStatus.FIVE_MIN_LOW_BROKEN);
								this.cancelAllOrders();
								// No trade we timed out
								_log.info("Rule 5min low broker Symbol: "
										+ getSymbol() + " Time: " + startPeriod);
								this.cancel();
							}
						} else {

							if (currentCandleItem.getVwap() > firstCandle
									.getHigh()) {
								updateTradestrategyStatus(TradestrategyStatus.FIVE_MIN_HIGH_BROKEN);
								this.cancelAllOrders();
								// No trade we timed out
								_log.info("Rule 5min high broker Symbol: "
										+ getSymbol() + " Time: " + startPeriod);
								this.cancel();
							}
						}
					}
				}

				if (!startPeriod.before(TradingCalendar.getSpecificTime(
						startPeriod, 10, 50))) {
					_log.info("Rule 10:30:00 bar, time out unfilled open position Symbol: "
							+ getSymbol() + " Time: " + startPeriod);
					if (!this.isThereOpenPosition()
							&& !TradestrategyStatus.CANCELLED
									.equals(getTradestrategy().getStatus())) {
						updateTradestrategyStatus(TradestrategyStatus.TO);
						this.cancelAllOrders();
						// No trade we timed out
						_log.info("Rule 10:30:00 bar, time out unfilled open position Symbol: "
								+ getSymbol() + " Time: " + startPeriod);
					}
					this.cancel();
				}
			}
		} catch (StrategyRuleException ex) {
			_log.error("Error  runRule exception: " + ex.getMessage(), ex);
			error(1, 10, "Error  runRule exception: " + ex.getMessage());
		}
	}
}