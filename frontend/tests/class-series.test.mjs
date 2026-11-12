import assert from "node:assert/strict";
import test from "node:test";
import { parseSeriesForm } from "../src/lib/staff-forms.ts";

const batch = "6f2d3c1e-0000-4000-8000-000000000001";
const form = (fields, lists = {}) => {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  for (const [key, values] of Object.entries(lists)) for (const value of values) data.append(key, value);
  return data;
};
const good = { title: " Waves ", firstDate: "2027-01-04", lastDate: "2027-01-31", startTime: "18:00", endTime: "20:00",
  zoomWebinarId: "812 3456 7890", skipDates: "2027-01-13,\n2027-01-11  2027-01-13" };
const lists = { weekdays: ["WEDNESDAY", "MONDAY"], batchIds: [batch] };

test("a weekly series is read in weekday order with holidays de-duplicated and sorted", () => {
  assert.deepEqual(parseSeriesForm(form(good, lists)), {
    title: "Waves", firstDate: "2027-01-04", lastDate: "2027-01-31", weekdays: ["MONDAY", "WEDNESDAY"], startTime: "18:00",
    endTime: "20:00", timeZone: "Asia/Colombo", batchIds: [batch], zoomWebinarId: "81234567890", skipDates: ["2027-01-11", "2027-01-13"],
  });
});

test("series form problems", () => {
  const error = (fields, l = lists) => parseSeriesForm(form({ ...good, ...fields }, l)).error;
  assert.equal(error({ firstDate: "" }), "Enter the first date.");
  assert.equal(error({ lastDate: "2027-01-01" }), "Enter a last date on or after the first date.");
  assert.equal(error({ lastDate: "2028-02-01" }), "A series can span at most a year.");
  assert.equal(error({}, { batchIds: [batch] }), "Choose at least one weekday.");
  assert.equal(error({}, { weekdays: ["FUNDAY"], batchIds: [batch] }), "Choose at least one weekday.");
  assert.equal(error({ skipDates: "2027-02-30" }), "“2027-02-30” is not a date. Write dates to skip as YYYY-MM-DD.");
  assert.equal(error({ endTime: "17:00" }), "The class must end after it starts, on the same day.");
  assert.equal(error({}, { weekdays: ["MONDAY"] }), "Choose at least one batch.");
});
