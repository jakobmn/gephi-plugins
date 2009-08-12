/*
Copyright 2008 WebAtlas
Authors : Mathieu Bastian, Mathieu Jacomy, Julian Bilcke
Website : http://www.gephi.org

This file is part of Gephi.

Gephi is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Gephi is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Gephi.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gephi.layout.api;

import java.util.List;
import java.util.Observer;

/**
 *
 * @author Mathieu Bastian
 */
public interface LayoutController {

    public void setLayout(Layout layout);

    public List<LayoutBuilder> getLayouts();

    public void executeLayout();

    public boolean canExecute();

    public void stopLayout();

    public boolean canStop();

    public void addObserver(Observer observer);

    public void deleteObserver(Observer observer);
}
