package ru.gdebenz.fuel.api;

import java.util.List;

/** Station metadata: {@code f} is the list of fuel grades normally sold here. */
public record StationMetaDto(List<String> f) {
}
