/*
 * deskflow -- mouse and keyboard sharing utility
 * Copyright (C) 2010 Shaun Patterson
 * Copyright (C) 2010 The Deskflow Project
 * Copyright (C) 2009 The Deskflow+ Project
 * Copyright (C) 2002 Chris Schoeneman
 * Copyright (C) 2025 Jonathan Glanz
 * Copyright (C) 2026 juliandigitalproducts
 * 
 * This package is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2, found in the file COPYING that should have accompanied this file.
 * 
 * This package is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tfv.deskflow.client.events

import java.io.Serializable
import org.tfv.deskflow.client.models.ClipboardData

/**
 * Originally written by Shaun Patterson for synergy-android; carried over via jglanz/deskflow-android.
 * See NOTICE.
 *
 * @author Shaun Patterson
 */
sealed class ScreenEvent : ClientEvent(), Serializable {
  data object AckReceived : ScreenEvent()
  data object Enter : ScreenEvent()
  data object Leave : ScreenEvent()
  data class SetClipboard(val data: ClipboardData) : ScreenEvent(), Serializable
}
