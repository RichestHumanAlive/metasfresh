package de.metas.material.planning.ddorder;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import de.metas.bpartner.BPartnerId;
import de.metas.material.event.ModelProductDescriptorExtractor;
import de.metas.material.event.commons.ProductDescriptor;
import de.metas.material.event.ddorder.DDOrder;
import de.metas.material.event.ddorder.DDOrderLine;
import de.metas.material.planning.MaterialPlanningContext;
import de.metas.material.planning.ProductPlanning;
import de.metas.material.planning.event.MaterialRequest;
import de.metas.material.planning.exception.MrpException;
import de.metas.organization.OrgId;
import de.metas.product.ProductId;
import de.metas.product.ResourceId;
import de.metas.quantity.Quantity;
import de.metas.shipping.ShipperId;
import de.metas.uom.IUOMConversionBL;
import de.metas.util.Loggables;
import de.metas.util.Services;
import de.metas.util.lang.Percent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.mm.attributes.AttributeSetInstanceId;
import org.adempiere.mm.attributes.api.PlainAttributeSetInstanceAware;
import org.adempiere.warehouse.LocatorId;
import org.adempiere.warehouse.WarehouseId;
import org.adempiere.warehouse.api.IWarehouseBL;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/*
 * #%L
 * metasfresh-mrp
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
@Service
@RequiredArgsConstructor
public class DDOrderPojoSupplier
{
	@NonNull private final IWarehouseBL warehouseBL = Services.get(IWarehouseBL.class);
	@NonNull private final DistributionNetworkRepository distributionNetworkRepository;
	@NonNull private final ModelProductDescriptorExtractor productDescriptorFactory;

	public List<DDOrder> supplyPojos(@NonNull final MaterialRequest request)
	{
		try
		{
			return supplyPojos0(request);
		}
		catch (final RuntimeException e)
		{
			throw new AdempiereException("Caught " + e.getClass().getSimpleName() + " trying to create DDOrders for a materialRequest", e)
					.appendParametersToMessage()
					.setParameter("request", request);
		}
	}

	public List<DDOrder> supplyPojos0(@NonNull final MaterialRequest request)
	{

		final List<DDOrder.DDOrderBuilder> builders = new ArrayList<>();

		final MaterialPlanningContext context = request.getContext();

		final ProductPlanning productPlanningData = context.getProductPlanning();
		final ResourceId plantId = context.getPlantId();

		// TODO vpj-cd I need to create logic for DRP-040 Shipment Due Action Notice
		// Indicates that a shipment for a Order Distribution is due.
		// Action should be taken at the source warehouse to ensure that the order is received on time.

		// TODO vpj-cd I need to create logic for DRP-050 Shipment Pas Due Action Notice
		// Indicates that a shipment for a Order Distribution is past due. You should either delay the orders created the requirement for the product
		// or expedite them when the product does arrive.

		if (productPlanningData.getDistributionNetworkId() == null)
		{
			// Indicates that the Product Planning Data for this product does not specify a valid network distribution.
			Loggables.addLog(
					"PP_Product_Planning has no DD_NetworkDistribution_ID; {} returns entpy list; productPlanningData={}",
					this.getClass(), productPlanningData);

			return ImmutableList.of();
		}

		final DistributionNetwork network = distributionNetworkRepository.getById(productPlanningData.getDistributionNetworkId());
		final List<DistributionNetworkLine> networkLines = network.getLinesByTargetWarehouse(productPlanningData.getWarehouseId());
		if (networkLines.isEmpty())
		{
			// No network lines were found for our target warehouse
			final WarehouseId warehouseToId = productPlanningData.getWarehouseId();
			Loggables.addLog(
					"DD_NetworkDistribution has no lines for target M_Warehouse_ID={}; {} returns empty list; "
							+ "networkDistribution={}"
							+ "warehouseToId={}",
					productPlanningData.getWarehouseId(), this.getClass(), network, warehouseToId);
			return ImmutableList.of();
		}

		final Instant supplyDateFinishSchedule = request.getDemandDate();

		ShipperId M_Shipper_ID = null;
		// I_DD_Order order = null;
		DDOrder.DDOrderBuilder ddOrderBuilder = null;

		Quantity qtyToSupplyRemaining = request.getQtyToSupply();
		for (final DistributionNetworkLine networkLine : networkLines)
		{
			// Check: if we created DD Orders for all qty that needed to be supplied, stop here
			if (qtyToSupplyRemaining.signum() <= 0)
			{
				break;
			}

			// get supply source warehouse and locator
			final WarehouseId warehouseFromId = networkLine.getSourceWarehouseId();

			// get supply target warehouse and locator
			final WarehouseId warehouseToId = networkLine.getTargetWarehouseId();
			final LocatorId locatorToId = warehouseBL.getOrCreateDefaultLocatorId(warehouseToId);

			// Get the warehouse in transit
			final OrgId warehouseFromOrgId = warehouseBL.getWarehouseOrgId(warehouseFromId);
			final WarehouseId warehouseInTrasitId = DDOrderUtil.retrieveInTransitWarehouseIdIfExists(warehouseFromOrgId).orElse(null);
			if (warehouseInTrasitId == null)
			{
				// DRP-010: Do not exist Transit Warehouse to this Organization
				Loggables.addLog(
						"No in-transit warehouse found for AD_Org_ID={} of the source warehouse; {} returns entpy list; "
								+ "networkLine={}"
								+ "network={}"
								+ "warehouseFromId={}",
						warehouseFromOrgId.getRepoId(), this.getClass(), networkLine, network, warehouseFromId);
				continue;
			}

			if (!ShipperId.equals(M_Shipper_ID, networkLine.getShipperId())) // this is also the case on our first iteration since we initialized M_Shipper_ID=null
			{
				final OrgId warehouseToOrgId = warehouseBL.getWarehouseOrgId(warehouseToId);
				// Org Must be linked to BPartner
				// final OrgId locatorToOrgId = warehouseBL.getLocatorOrgId(locatorToId); // we strongly assume that we can got with the warehouse's org and don't need to retrieve its default locator's org!
				final BPartnerId orgBPartnerId = DDOrderUtil.retrieveOrgBPartnerId(warehouseToOrgId).orElse(null);
				if (orgBPartnerId == null)
				{
					// DRP-020: Target Org has no BP linked to it
					Loggables.addLog(
							"No org-bpartner found for AD_Org_ID={} of target warehouse; {} returns entpy list; "
									+ "networkLine={}"
									+ "network={}"
									+ "warehouseToId={}"
									+ "locatorToId={}",
							warehouseToOrgId.getRepoId(), this.getClass(), networkLine, network, warehouseToId, locatorToId);
					continue;
				}

				//
				// Try to find some DD_Order with Shipper , Business Partner and Doc Status = Draft
				// Consolidate the demand in a single order for each Shipper , Business Partner , DemandDateStartSchedule
				ddOrderBuilder = DDOrder.builder()
						.orgId(warehouseToOrgId)
						.plantId(plantId)
						.productPlanningId(productPlanningData.getId())
						.datePromised(supplyDateFinishSchedule)
						.shipperId(networkLine.getShipperId())
						.simulated(request.isSimulated());

				builders.add(ddOrderBuilder);

				M_Shipper_ID = networkLine.getShipperId();
			}

			//
			// Crate DD order line
			final Quantity qtyToMove = calculateQtyToMove(qtyToSupplyRemaining, networkLine.getTransferPercent());

			final DDOrderLine ddOrderLine = createDD_OrderLine(network.getId(), networkLine, qtyToMove, request);
			ddOrderBuilder.line(ddOrderLine);

			qtyToSupplyRemaining = qtyToSupplyRemaining.subtract(qtyToMove);
		} // end of the for-loop over networkLines

		//
		// Check: remaining qtyToSupply shall be ZERO
		if (qtyToSupplyRemaining.signum() != 0)
		{
			// TODO: introduce DRP-XXX notice
			throw new MrpException("Cannot create DD Order for required Qty To Supply.")
					.setParameter("QtyToSupply", request.getQtyToSupply())
					.setParameter("QtyToSupply (remaining)", qtyToSupplyRemaining)
					.setParameter("@DD_NetworkDistribution_ID@", network)
					.setParameter("@DD_NetworkDistributionLine_ID@", networkLines)
					.setParameter("context", context);
		}

		return builders.stream()
				.map(DDOrder.DDOrderBuilder::build)
				.collect(Collectors.toList());
	}

	@VisibleForTesting
	/* package */ final Quantity calculateQtyToMove(
			@NonNull final Quantity qtyToMoveRequested,
			@NonNull final Percent networkLineTransferPercent)
	{
		if (networkLineTransferPercent.signum() < 0)
		{
			throw new MrpException("NetworkLine's TransferPercent shall not be negative")
					.setParameter("QtyToMove", qtyToMoveRequested)
					.setParameter("Transfer Percent", networkLineTransferPercent);
		}

		return qtyToMoveRequested.multiply(networkLineTransferPercent);
	}

	private DDOrderLine createDD_OrderLine(
			@NonNull final DistributionNetworkId networkId,
			@NonNull final DistributionNetworkLine networkLine,
			@NonNull final Quantity qtyToMove,
			@NonNull final MaterialRequest request)
	{
		final MaterialPlanningContext context = request.getContext();

		final PlainAttributeSetInstanceAware asiAware = PlainAttributeSetInstanceAware
				.forProductIdAndAttributeSetInstanceId(
						ProductId.toRepoId(context.getProductId()),
						AttributeSetInstanceId.toRepoId(context.getAttributeSetInstanceId()));

		final ProductDescriptor productDescriptor = productDescriptorFactory.createProductDescriptor(asiAware);

		final int durationDays = DDOrderUtil.calculateDurationDays(context.getProductPlanning(), networkLine);

		final Quantity qtyToMoveInProductUOM = Services.get(IUOMConversionBL.class).convertToProductUOM(qtyToMove, asiAware.getProductId());

		return DDOrderLine.builder()
				.salesOrderLineId(request.getMrpDemandOrderLineSOId())
				.bPartnerId(request.getMrpDemandBPartnerId())
				.productDescriptor(productDescriptor)
				.qty(qtyToMoveInProductUOM.toBigDecimal())
				.distributionNetworkAndLineId(DistributionNetworkAndLineId.of(networkId, networkLine.getId()))
				.durationDays(durationDays)
				.build();
	}

}
