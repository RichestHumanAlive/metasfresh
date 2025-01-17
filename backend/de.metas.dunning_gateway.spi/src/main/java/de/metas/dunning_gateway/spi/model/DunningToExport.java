package de.metas.dunning_gateway.spi.model;

import de.metas.invoice.InvoiceId;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;

/*
 * #%L
 * metasfresh-invoice.gateway.commons
 * %%
 * Copyright (C) 2018 metas GmbH
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

@Value
@Builder(toBuilder = true)
public class DunningToExport
{
	@NonNull
	DunningId id;

	@NonNull
	InvoiceId invoiceId;

	@NonNull
	Calendar dunningDate;

	@NonNull
	Instant dunningTimestamp;

	@NonNull
	String documentNumber;

	@Nullable
	String dunningText;

	@NonNull
	MetasfreshVersion metasfreshVersion;

	@NonNull
	Money amount;

	@NonNull
	Money alreadyPaidAmount;

	@NonNull
	BPartnerId recipientId;

	@Singular
	List<DunningAttachment> dunningAttachments;

}
