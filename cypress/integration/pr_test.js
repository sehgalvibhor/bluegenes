describe("UI Pre-Commit Test", function() {
  beforeEach(function() {
    cy.visit("/");
    /*
    cy.get("#bluegenes-main-nav").within(() => {
      cy.get("ul")
        .find("li.dropdown.mine-settings.secondary-nav")
        .click()
        .contains("FlyMine")
        .click();
    });
    */
  });

  it("Visits Website and Logs in using Dummy details", function() {
    cy.contains("Log In").click();
    cy.get("#email").type("demo@intermine.org");
    cy.get("#password").type("demo");
    cy.get("form")
      .find("button")
      .click();
    cy.contains("demo@intermine.org"); //Use assertion statement here
  });

  it("Perform a region search using existing example", function() {
    cy.server();
    cy.route("POST", "*/service/query/results").as("getData");
    cy.contains("Regions").click();
    cy.get(".guidance")
      .contains("[Show me an example]")
      .click();
    cy.get(".region-text > .form-control").should("not.be.empty");
    cy.get("button")
      .contains("Search")
      .click();
    cy.get(".results-summary").should("have", "Results");
    cy.wait("@getData");
    cy.get("@getData").should(xhr => {
        expect(xhr.status).to.equal(200);
    });
  });
});
